// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/fs/file_block_manager.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "storage/fs/file_block_manager.h"

#include <atomic>
#include <cstddef>
#include <memory>
#include <numeric>
#include <string>
#include <utility>

#include "common/config.h"
#include "common/logging.h"
#include "env/env.h"
#include "env/env_util.h"
#include "gutil/strings/substitute.h"
#include "storage/fs/block_id.h"
#include "storage/storage_engine.h"
#include "util/file_cache.h"
#include "util/path_util.h"
#include "util/slice.h"

using std::accumulate;
using std::shared_ptr;
using std::string;

using strings::Substitute;

namespace starrocks::fs {

namespace internal {

////////////////////////////////////////////////////////////
// FileWritableBlock
////////////////////////////////////////////////////////////

// A file-backed block that has been opened for writing.
//
// Contains a pointer to the block manager as well as file path
// so that dirty metadata can be synced via BlockManager::SyncMetadata()
// at Close() time. Embedding a file path (and not a simpler
// BlockId) consumes more memory, but the number of outstanding
// FileWritableBlock instances is expected to be low.
class FileWritableBlock : public WritableBlock {
public:
    FileWritableBlock(FileBlockManager* block_manager, string path, shared_ptr<WritableFile> writer);

    ~FileWritableBlock() override;

    Status close() override;

    Status abort() override;

    BlockManager* block_manager() const override;

    const BlockId& id() const override;
    const std::string& path() const override;

    Status append(const Slice& data) override;

    Status appendv(const Slice* data, size_t data_cnt) override;

    Status finalize() override;

    size_t bytes_appended() const override;

    void set_bytes_appended(size_t bytes_appended) override { _bytes_appended = bytes_appended; }

    State state() const override;

    void handle_error(const Status& s) const;

    // Starts an asynchronous flush of dirty block data to disk.
    Status flush_data_async();

private:
    FileWritableBlock(const FileWritableBlock&) = delete;
    const FileWritableBlock& operator=(const FileWritableBlock&) = delete;

    enum SyncMode { SYNC, NO_SYNC };

    // Close the block, optionally synchronizing dirty data and metadata.
    Status _close(SyncMode mode);

    // Back pointer to the block manager.
    //
    // Should remain alive for the lifetime of this block.
    FileBlockManager* _block_manager;

    const BlockId _block_id;
    const string _path;

    // The underlying opened file backing this block.
    shared_ptr<WritableFile> _writer;

    State _state;

    // The number of bytes successfully appended to the block.
    size_t _bytes_appended;
};

FileWritableBlock::FileWritableBlock(FileBlockManager* block_manager, string path, shared_ptr<WritableFile> writer)
        : _block_manager(block_manager),
          _path(std::move(path)),
          _writer(std::move(writer)),
          _state(CLEAN),
          _bytes_appended(0) {}

FileWritableBlock::~FileWritableBlock() {
    if (_state != CLOSED) {
        WARN_IF_ERROR(abort(), strings::Substitute("Failed to close block $0", _path));
    }
}

Status FileWritableBlock::close() {
    return _close(SYNC);
}

Status FileWritableBlock::abort() {
    RETURN_IF_ERROR(_close(NO_SYNC));
    return _block_manager->_delete_block(_path);
}

BlockManager* FileWritableBlock::block_manager() const {
    return _block_manager;
}

const BlockId& FileWritableBlock::id() const {
    CHECK(false) << "Not support Block.id(). (TODO)";
    return _block_id;
}

const string& FileWritableBlock::path() const {
    return _path;
}

Status FileWritableBlock::append(const Slice& data) {
    return appendv(&data, 1);
}

Status FileWritableBlock::appendv(const Slice* data, size_t data_cnt) {
    DCHECK(_state == CLEAN || _state == DIRTY) << "path=" << _path << " invalid state=" << _state;
    RETURN_IF_ERROR(_writer->appendv(data, data_cnt));
    _state = DIRTY;

    // Calculate the amount of data written
    size_t bytes_written = accumulate(data, data + data_cnt, static_cast<size_t>(0),
                                      [&](int sum, const Slice& curr) { return sum + curr.size; });
    _bytes_appended += bytes_written;
    return Status::OK();
}

Status FileWritableBlock::flush_data_async() {
    VLOG(3) << "Flushing block " << _path;
    RETURN_IF_ERROR(_writer->flush(WritableFile::FLUSH_ASYNC));
    return Status::OK();
}

Status FileWritableBlock::finalize() {
    DCHECK(_state == CLEAN || _state == DIRTY || _state == FINALIZED)
            << "path=" << _path << "Invalid state: " << _state;

    if (_state == FINALIZED) {
        return Status::OK();
    }
    VLOG(3) << "Finalizing block " << _path;
    if (_state == DIRTY && BlockManager::block_manager_preflush_control == "finalize") {
        flush_data_async();
    }
    _state = FINALIZED;
    return Status::OK();
}

size_t FileWritableBlock::bytes_appended() const {
    return _bytes_appended;
}

WritableBlock::State FileWritableBlock::state() const {
    return _state;
}

Status FileWritableBlock::_close(SyncMode mode) {
    if (_state == CLOSED) {
        return Status::OK();
    }

    Status sync;
    if (mode == SYNC && (_state == CLEAN || _state == DIRTY || _state == FINALIZED)) {
        // Safer to synchronize data first, then metadata.
        VLOG(3) << "Syncing block " << _path;
        sync = _writer->sync();
        WARN_IF_ERROR(sync, strings::Substitute("Failed to sync when closing block $0", _path));
    }
    Status close = _writer->close();

    _state = CLOSED;
    _writer.reset();
    // Either Close() or Sync() could have run into an error.
    RETURN_IF_ERROR(close);
    RETURN_IF_ERROR(sync);

    // Prefer the result of Close() to that of Sync().
    return close.ok() ? close : sync;
}

////////////////////////////////////////////////////////////
// FileReadableBlock
////////////////////////////////////////////////////////////

// A file-backed block that has been opened for reading.
//
// There may be millions of instances of FileReadableBlock outstanding, so
// great care must be taken to reduce its size. To that end, it does _not_
// embed a FileBlockLocation, using the simpler BlockId instead.
class FileReadableBlock : public ReadableBlock {
public:
    FileReadableBlock(FileBlockManager* block_manager, string path,
                      std::shared_ptr<OpenedFileHandle<RandomAccessFile>> file_handle);

    ~FileReadableBlock() override;

    Status close() override;

    BlockManager* block_manager() const override;

    const BlockId& id() const override;
    const std::string& path() const override;

    Status size(uint64_t* sz) const override;

    Status read(uint64_t offset, Slice result) const override;

    Status readv(uint64_t offset, const Slice* results, size_t res_cnt) const override;

    void handle_error(const Status& s) const;

private:
    // Back pointer to the owning block manager.
    FileBlockManager* _block_manager;

    // The block's identifier.
    const BlockId _block_id;
    const string _path;

    // The underlying opened file backing this block.
    std::shared_ptr<OpenedFileHandle<RandomAccessFile>> _file_handle;
    // the backing file of OpenedFileHandle, not owned.
    RandomAccessFile* _file;

    // Whether or not this block has been closed. Close() is thread-safe, so
    // this must be an atomic primitive.
    std::atomic_bool _closed;

    FileReadableBlock(const FileReadableBlock&) = delete;
    const FileReadableBlock& operator=(const FileReadableBlock&) = delete;
};

FileReadableBlock::FileReadableBlock(FileBlockManager* block_manager, string path,
                                     std::shared_ptr<OpenedFileHandle<RandomAccessFile>> file_handle)
        : _block_manager(block_manager), _path(std::move(path)), _file_handle(std::move(file_handle)), _closed(false) {
    _file = _file_handle->file();
}

FileReadableBlock::~FileReadableBlock() {
    WARN_IF_ERROR(close(), strings::Substitute("Failed to close block $0", _path));
}

Status FileReadableBlock::close() {
    bool expected = false;
    if (_closed.compare_exchange_strong(expected, true)) {
        _file_handle.reset();
    }

    return Status::OK();
}

BlockManager* FileReadableBlock::block_manager() const {
    return _block_manager;
}

const BlockId& FileReadableBlock::id() const {
    CHECK(false) << "Not support Block.id(). (TODO)";
    return _block_id;
}

const string& FileReadableBlock::path() const {
    return _path;
}

Status FileReadableBlock::size(uint64_t* sz) const {
    DCHECK(!_closed.load());

    RETURN_IF_ERROR(_file->size(sz));
    return Status::OK();
}

Status FileReadableBlock::read(uint64_t offset, Slice result) const {
    return readv(offset, &result, 1);
}

Status FileReadableBlock::readv(uint64_t offset, const Slice* results, size_t res_cnt) const {
    DCHECK(!_closed.load());
    return _file->readv_at(offset, results, res_cnt);
}

} // namespace internal

////////////////////////////////////////////////////////////
// FileBlockManager
////////////////////////////////////////////////////////////

FileBlockManager::FileBlockManager(std::shared_ptr<Env> env, BlockManagerOptions opts)
        : _env(std::move(env)), _opts(std::move(opts)) {
#ifdef BE_TEST
    _file_cache = std::make_unique<FileCache<RandomAccessFile>>("Readable file cache",
                                                                config::file_descriptor_cache_capacity);
#else
    _file_cache = std::make_unique<FileCache<RandomAccessFile>>("Readable file cache",
                                                                StorageEngine::instance()->file_cache());
#endif
}

FileBlockManager::~FileBlockManager() = default;

Status FileBlockManager::open() {
    // TODO(lingbin)
    return Status::NotSupported("to be implemented. (TODO)");
}

Status FileBlockManager::create_block(const CreateBlockOptions& opts, std::unique_ptr<WritableBlock>* block) {
    CHECK(!_opts.read_only);

    shared_ptr<WritableFile> writer;
    WritableFileOptions wr_opts;
    wr_opts.mode = opts.mode;
    RETURN_IF_ERROR(env_util::open_file_for_write(wr_opts, _env.get(), opts.path, &writer));

    VLOG(1) << "Creating new block at " << opts.path;
    *block = std::make_unique<internal::FileWritableBlock>(this, opts.path, writer);
    return Status::OK();
}

Status FileBlockManager::open_block(const std::string& path, std::unique_ptr<ReadableBlock>* block) {
    VLOG(1) << "Opening block with path at " << path;
    std::shared_ptr<OpenedFileHandle<RandomAccessFile>> file_handle(new OpenedFileHandle<RandomAccessFile>());
    bool found = _file_cache->lookup(path, file_handle.get());
    if (!found) {
        ASSIGN_OR_RETURN(auto file, _env->new_random_access_file(path));
        _file_cache->insert(path, file.release(), file_handle.get());
    }

    *block = std::make_unique<internal::FileReadableBlock>(this, path, file_handle);
    return Status::OK();
}

void FileBlockManager::erase_block_cache(const std::string& path) {
    VLOG(1) << "erasing block cache with path at " << path;
    _file_cache->erase(path);
}

// TODO(lingbin): We should do something to ensure that deletion can only be done
// after the last reader or writer has finished
Status FileBlockManager::_delete_block(const string& path) {
    CHECK(!_opts.read_only);

    RETURN_IF_ERROR(_env->delete_file(path));

    // We don't bother fsyncing the parent directory as there's nothing to be
    // gained by ensuring that the deletion is made durable. Even if we did
    // fsync it, we'd need to account for garbage at startup time (in the
    // event that we crashed just before the fsync), and with such accounting
    // fsync-as-you-delete is unnecessary.
    //
    // The block's directory hierarchy is left behind. We could prune it if
    // it's empty, but that's racy and leaving it isn't much overhead.

    return Status::OK();
}

// TODO(lingbin): only one level is enough?
Status FileBlockManager::_sync_metadata(const string& path) {
    string dir = path_util::dir_name(path);
    RETURN_IF_ERROR(_env->sync_dir(dir));
    return Status::OK();
}

} // namespace starrocks::fs
