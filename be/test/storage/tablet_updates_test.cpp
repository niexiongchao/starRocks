// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "storage/tablet_updates.h"

#include <gtest/gtest.h>

#include <chrono>
#include <string>
#include <thread>

#include "column/datum_tuple.h"
#include "column/vectorized_fwd.h"
#include "gutil/strings/substitute.h"
#include "storage/kv_store.h"
#include "storage/primary_key_encoder.h"
#include "storage/rowset/rowset_factory.h"
#include "storage/rowset/rowset_writer.h"
#include "storage/rowset/rowset_writer_context.h"
#include "storage/rowset/vectorized/rowset_options.h"
#include "storage/snapshot_manager.h"
#include "storage/storage_engine.h"
#include "storage/tablet.h"
#include "storage/tablet_meta_manager.h"
#include "storage/update_manager.h"
#include "storage/vectorized/chunk_helper.h"
#include "storage/vectorized/empty_iterator.h"
#include "storage/vectorized/schema_change.h"
#include "storage/vectorized/union_iterator.h"
#include "storage/wrapper_field.h"
#include "testutil/assert.h"
#include "util/defer_op.h"
#include "util/path_util.h"

namespace starrocks {

class TabletUpdatesTest : public testing::Test {
public:
    RowsetSharedPtr create_rowset(const TabletSharedPtr& tablet, const vector<int64_t>& keys,
                                  vectorized::Column* one_delete = nullptr) {
        RowsetWriterContext writer_context(kDataFormatV2, config::storage_format_version);
        RowsetId rowset_id = StorageEngine::instance()->next_rowset_id();
        writer_context.rowset_id = rowset_id;
        writer_context.tablet_id = tablet->tablet_id();
        writer_context.tablet_schema_hash = tablet->schema_hash();
        writer_context.partition_id = 0;
        writer_context.rowset_type = BETA_ROWSET;
        writer_context.rowset_path_prefix = tablet->schema_hash_path();
        writer_context.rowset_state = COMMITTED;
        writer_context.tablet_schema = &tablet->tablet_schema();
        writer_context.version.first = 0;
        writer_context.version.second = 0;
        writer_context.segments_overlap = NONOVERLAPPING;
        std::unique_ptr<RowsetWriter> writer;
        EXPECT_TRUE(RowsetFactory::create_rowset_writer(writer_context, &writer).ok());
        auto schema = vectorized::ChunkHelper::convert_schema(tablet->tablet_schema());
        auto chunk = vectorized::ChunkHelper::new_chunk(schema, keys.size());
        auto& cols = chunk->columns();
        for (int64_t key : keys) {
            cols[0]->append_datum(vectorized::Datum(key));
            cols[1]->append_datum(vectorized::Datum((int16_t)(key % 100 + 1)));
            cols[2]->append_datum(vectorized::Datum((int32_t)(key % 1000 + 2)));
        }
        if (one_delete == nullptr && !keys.empty()) {
            CHECK_OK(writer->flush_chunk(*chunk));
        } else if (one_delete == nullptr) {
            CHECK_OK(writer->flush());
        } else if (one_delete != nullptr) {
            CHECK_OK(writer->flush_chunk_with_deletes(*chunk, *one_delete));
        }
        return *writer->build();
    }

    RowsetSharedPtr create_rowsets(const TabletSharedPtr& tablet, const vector<int64_t>& keys,
                                   std::size_t max_rows_per_segment) {
        RowsetWriterContext writer_context(kDataFormatV2, config::storage_format_version);
        RowsetId rowset_id = StorageEngine::instance()->next_rowset_id();
        writer_context.rowset_id = rowset_id;
        writer_context.tablet_id = tablet->tablet_id();
        writer_context.tablet_schema_hash = tablet->schema_hash();
        writer_context.partition_id = 0;
        writer_context.rowset_type = BETA_ROWSET;
        writer_context.rowset_path_prefix = tablet->schema_hash_path();
        writer_context.rowset_state = COMMITTED;
        writer_context.tablet_schema = &tablet->tablet_schema();
        writer_context.version.first = 0;
        writer_context.version.second = 0;
        writer_context.segments_overlap = NONOVERLAPPING;
        std::unique_ptr<RowsetWriter> writer;
        EXPECT_TRUE(RowsetFactory::create_rowset_writer(writer_context, &writer).ok());
        auto schema = vectorized::ChunkHelper::convert_schema(tablet->tablet_schema());
        for (std::size_t written_rows = 0; written_rows < keys.size(); written_rows += max_rows_per_segment) {
            auto chunk = vectorized::ChunkHelper::new_chunk(schema, max_rows_per_segment);
            auto& cols = chunk->columns();
            for (size_t i = 0; i < max_rows_per_segment; i++) {
                cols[0]->append_datum(vectorized::Datum(keys[written_rows + i]));
                cols[1]->append_datum(vectorized::Datum((int16_t)(keys[written_rows + i] % 100 + 1)));
                cols[2]->append_datum(vectorized::Datum((int32_t)(keys[written_rows + i] % 1000 + 2)));
            }
            CHECK_OK(writer->flush_chunk(*chunk));
        }
        return *writer->build();
    }

    TabletSharedPtr create_tablet(int64_t tablet_id, int32_t schema_hash) {
        TCreateTabletReq request;
        request.tablet_id = tablet_id;
        request.__set_version(1);
        request.__set_version_hash(0);
        request.tablet_schema.schema_hash = schema_hash;
        request.tablet_schema.short_key_column_count = 6;
        request.tablet_schema.keys_type = TKeysType::PRIMARY_KEYS;
        request.tablet_schema.storage_type = TStorageType::COLUMN;

        TColumn k1;
        k1.column_name = "pk";
        k1.__set_is_key(true);
        k1.column_type.type = TPrimitiveType::BIGINT;
        request.tablet_schema.columns.push_back(k1);

        TColumn k2;
        k2.column_name = "v1";
        k2.__set_is_key(false);
        k2.column_type.type = TPrimitiveType::SMALLINT;
        request.tablet_schema.columns.push_back(k2);

        TColumn k3;
        k3.column_name = "v2";
        k3.__set_is_key(false);
        k3.column_type.type = TPrimitiveType::INT;
        request.tablet_schema.columns.push_back(k3);
        auto st = StorageEngine::instance()->create_tablet(request);
        CHECK(st.ok()) << st.to_string();
        return StorageEngine::instance()->tablet_manager()->get_tablet(tablet_id, false);
    }

    TabletSharedPtr create_tablet2(int64_t tablet_id, int32_t schema_hash) {
        TCreateTabletReq request;
        request.tablet_id = tablet_id;
        request.__set_version(1);
        request.__set_version_hash(0);
        request.tablet_schema.schema_hash = schema_hash;
        request.tablet_schema.short_key_column_count = 6;
        request.tablet_schema.keys_type = TKeysType::PRIMARY_KEYS;
        request.tablet_schema.storage_type = TStorageType::COLUMN;

        TColumn k1;
        k1.column_name = "pk";
        k1.__set_is_key(true);
        k1.column_type.type = TPrimitiveType::BIGINT;
        request.tablet_schema.columns.push_back(k1);

        TColumn k2;
        k2.column_name = "v1";
        k2.__set_is_key(false);
        k2.column_type.type = TPrimitiveType::SMALLINT;
        request.tablet_schema.columns.push_back(k2);

        TColumn k3;
        k3.column_name = "v2";
        k3.__set_is_key(false);
        k3.column_type.type = TPrimitiveType::INT;
        request.tablet_schema.columns.push_back(k3);

        TColumn k4;
        k4.column_name = "v3";
        k4.__set_is_key(false);
        k4.column_type.type = TPrimitiveType::INT;
        k4.__set_default_value("1");
        request.tablet_schema.columns.push_back(k4);
        auto st = StorageEngine::instance()->create_tablet(request);
        CHECK(st.ok()) << st.to_string();
        return StorageEngine::instance()->tablet_manager()->get_tablet(tablet_id, false);
    }

    TabletSharedPtr create_tablet_to_schema_change(int64_t tablet_id, int32_t schema_hash) {
        TCreateTabletReq request;
        request.tablet_id = tablet_id;
        request.__set_version(1);
        request.__set_version_hash(0);
        request.tablet_schema.schema_hash = schema_hash;
        request.tablet_schema.short_key_column_count = 6;
        request.tablet_schema.keys_type = TKeysType::PRIMARY_KEYS;
        request.tablet_schema.storage_type = TStorageType::COLUMN;

        TColumn k1;
        k1.column_name = "pk";
        k1.__set_is_key(true);
        k1.column_type.type = TPrimitiveType::INT;
        request.tablet_schema.columns.push_back(k1);

        TColumn k2;
        k2.column_name = "v1";
        k2.__set_is_key(false);
        k2.column_type.type = TPrimitiveType::SMALLINT;
        request.tablet_schema.columns.push_back(k2);

        TColumn k3;
        k3.column_name = "v2";
        k3.__set_is_key(false);
        k3.column_type.type = TPrimitiveType::VARCHAR;
        request.tablet_schema.columns.push_back(k3);

        TColumn k4;
        k4.column_name = "v3";
        k4.__set_is_key(false);
        k4.column_type.type = TPrimitiveType::INT;
        k4.__set_default_value("1");
        request.tablet_schema.columns.push_back(k4);
        auto st = StorageEngine::instance()->create_tablet(request);
        CHECK(st.ok()) << st.to_string();
        return StorageEngine::instance()->tablet_manager()->get_tablet(tablet_id, false);
    }

    void SetUp() override {
        _compaction_mem_tracker.reset(new MemTracker(-1));
        _tablet_meta_mem_tracker = std::make_unique<MemTracker>();
    }

    void TearDown() override {
        if (_tablet2) {
            StorageEngine::instance()->tablet_manager()->drop_tablet(_tablet2->tablet_id());
            _tablet2.reset();
        }
        if (_tablet) {
            StorageEngine::instance()->tablet_manager()->drop_tablet(_tablet->tablet_id());
            _tablet.reset();
        }
    }

    static Status full_clone(const TabletSharedPtr& source_tablet, int clone_version,
                             const TabletSharedPtr& dest_tablet) {
        auto snapshot_dir = SnapshotManager::instance()->snapshot_full(source_tablet, clone_version, 3600);
        CHECK(snapshot_dir.ok()) << snapshot_dir.status();

        DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

        auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(source_tablet, *snapshot_dir);
        auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
        CHECK(snapshot_meta.ok()) << snapshot_meta.status();

        RETURN_IF_ERROR(SnapshotManager::instance()->assign_new_rowset_id(&(*snapshot_meta), meta_dir));

        std::set<std::string> files;
        auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
        CHECK(st.ok()) << st;
        files.erase("meta");

        for (const auto& f : files) {
            std::string src = meta_dir + "/" + f;
            std::string dst = dest_tablet->schema_hash_path() + "/" + f;
            st = Env::Default()->link_file(src, dst);
            if (st.ok()) {
                LOG(INFO) << "Linked " << src << " to " << dst;
            } else if (st.is_already_exist()) {
                LOG(INFO) << dst << " already exist";
            } else {
                return st;
            }
        }
        // Pretend that source_tablet is a peer replica of dest_tablet
        snapshot_meta->tablet_meta().set_tablet_id(dest_tablet->tablet_id());
        snapshot_meta->tablet_meta().set_schema_hash(dest_tablet->schema_hash());
        for (auto& rm : snapshot_meta->rowset_metas()) {
            rm.set_tablet_id(dest_tablet->tablet_id());
        }

        st = dest_tablet->updates()->load_snapshot(*snapshot_meta);
        dest_tablet->updates()->remove_expired_versions(time(NULL));
        return st;
    }

    static StatusOr<TabletSharedPtr> clone_a_new_replica(const TabletSharedPtr& source_tablet, int64_t new_tablet_id) {
        auto clone_version = source_tablet->max_version().second;
        auto snapshot_dir = SnapshotManager::instance()->snapshot_full(source_tablet, clone_version, 3600);
        CHECK(snapshot_dir.ok()) << snapshot_dir.status();

        DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

        auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(source_tablet, *snapshot_dir);
        auto meta_file = meta_dir + "/meta";
        auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_file);
        CHECK(snapshot_meta.ok()) << snapshot_meta.status();

        // Assign a new tablet_id and overwrite the meta file.
        snapshot_meta->tablet_meta().set_tablet_id(new_tablet_id);
        CHECK(snapshot_meta->serialize_to_file(meta_file).ok());

        RETURN_IF_ERROR(SnapshotManager::instance()->assign_new_rowset_id(&(*snapshot_meta), meta_dir));

        auto store = source_tablet->data_dir();
        auto new_schema_hash = source_tablet->schema_hash();
        std::string new_tablet_path = store->path() + DATA_PREFIX;
        new_tablet_path = path_util::join_path_segments(new_tablet_path, std::to_string(source_tablet->shard_id()));
        new_tablet_path = path_util::join_path_segments(new_tablet_path, std::to_string(new_tablet_id));
        new_tablet_path = path_util::join_path_segments(new_tablet_path, std::to_string(new_schema_hash));
        CHECK(std::filesystem::create_directories(new_tablet_path));

        std::set<std::string> files;
        CHECK(FileUtils::list_dirs_files(meta_dir, NULL, &files).ok());
        for (const auto& f : files) {
            std::string src = meta_dir + "/" + f;
            std::string dst = new_tablet_path + "/" + f;
            Status st = Env::Default()->link_file(src, dst);
            if (st.ok()) {
                LOG(INFO) << "Linked " << src << " to " << dst;
            } else if (st.is_already_exist()) {
                LOG(INFO) << dst << " already exist";
            } else {
                return st;
            }
        }

        auto tablet_manager = StorageEngine::instance()->tablet_manager();
        auto st = tablet_manager->create_tablet_from_meta_snapshot(store, new_tablet_id, new_schema_hash,
                                                                   new_tablet_path);
        CHECK(st.ok()) << st;
        return tablet_manager->get_tablet(new_tablet_id, false);
    }

protected:
    TabletSharedPtr _tablet;
    TabletSharedPtr _tablet2;
    std::unique_ptr<MemTracker> _compaction_mem_tracker;
    std::unique_ptr<MemTracker> _tablet_meta_mem_tracker;
};

static TabletSharedPtr load_same_tablet_from_store(MemTracker* mem_tracker, const TabletSharedPtr& tablet) {
    auto data_dir = tablet->data_dir();
    auto tablet_id = tablet->tablet_id();
    auto schema_hash = tablet->schema_hash();

    std::string enc_key = strings::Substitute("tabletmeta_$0_$1", tablet_id, schema_hash);
    std::string serialized_meta;
    auto meta = tablet->data_dir()->get_meta();
    auto st = meta->get(META_COLUMN_FAMILY_INDEX, enc_key, &serialized_meta);
    CHECK(st.ok()) << st;

    // Parse tablet meta.
    auto tablet_meta = std::make_shared<TabletMeta>();
    CHECK(tablet_meta->deserialize(serialized_meta).ok());

    // Create a new tablet instance from the latest snapshot.
    auto tablet1 = Tablet::create_tablet_from_meta(mem_tracker, tablet_meta, data_dir);
    CHECK(tablet1 != nullptr);
    CHECK(tablet1->init().ok());
    CHECK(tablet1->init_succeeded());
    return tablet1;
}

static vectorized::ChunkIteratorPtr create_tablet_iterator(const TabletSharedPtr& tablet, int64_t version) {
    static OlapReaderStatistics s_stats;
    vectorized::Schema schema = vectorized::ChunkHelper::convert_schema(tablet->tablet_schema());
    vectorized::RowsetReadOptions rs_opts;
    rs_opts.is_primary_keys = true;
    rs_opts.sorted = false;
    rs_opts.version = version;
    rs_opts.meta = tablet->data_dir()->get_meta();
    rs_opts.stats = &s_stats;
    auto seg_iters = tablet->capture_segment_iterators(Version(0, version), schema, rs_opts);
    if (!seg_iters.ok()) {
        LOG(ERROR) << "read tablet failed: " << seg_iters.status().to_string();
        return nullptr;
    }
    if (seg_iters->empty()) {
        return vectorized::new_empty_iterator(schema, DEFAULT_CHUNK_SIZE);
    }
    return vectorized::new_union_iterator(*seg_iters);
}

static ssize_t read_and_compare(const vectorized::ChunkIteratorPtr& iter, const vector<int64_t>& keys) {
    auto chunk = vectorized::ChunkHelper::new_chunk(iter->schema(), 100);
    auto full_chunk = vectorized::ChunkHelper::new_chunk(iter->schema(), keys.size());
    auto& cols = full_chunk->columns();
    for (size_t i = 0; i < keys.size(); i++) {
        cols[0]->append_datum(vectorized::Datum(keys[i]));
        cols[1]->append_datum(vectorized::Datum((int16_t)(keys[i] % 100 + 1)));
        cols[2]->append_datum(vectorized::Datum((int32_t)(keys[i] % 1000 + 2)));
    }
    size_t count = 0;
    while (true) {
        auto st = iter->get_next(chunk.get());
        if (st.is_end_of_file()) {
            break;
        } else if (st.ok()) {
            for (auto i = 0; i < chunk->num_rows(); i++) {
                EXPECT_EQ(full_chunk->get(count + i).compare(iter->schema(), chunk->get(i)), 0);
            }
            count += chunk->num_rows();
            chunk->reset();
        } else {
            return -1;
        }
    }
    return count;
}

static ssize_t read_until_eof(const vectorized::ChunkIteratorPtr& iter) {
    auto chunk = vectorized::ChunkHelper::new_chunk(iter->schema(), 100);
    size_t count = 0;
    while (true) {
        auto st = iter->get_next(chunk.get());
        if (st.is_end_of_file()) {
            break;
        } else if (st.ok()) {
            count += chunk->num_rows();
            chunk->reset();
        } else {
            LOG(WARNING) << "read error: " << st.to_string();
            return -1;
        }
    }
    return count;
}

static ssize_t read_tablet(const TabletSharedPtr& tablet, int64_t version) {
    auto iter = create_tablet_iterator(tablet, version);
    if (iter == nullptr) {
        return -1;
    }
    return read_until_eof(iter);
}

static ssize_t read_tablet_and_compare(const TabletSharedPtr& tablet, int64_t version, const vector<int64_t>& keys) {
    auto iter = create_tablet_iterator(tablet, version);
    if (iter == nullptr) {
        return -1;
    }
    return read_and_compare(iter, keys);
}

static ssize_t read_tablet_and_compare_schema_changed(const TabletSharedPtr& tablet, int64_t version,
                                                      const vector<int64_t>& keys) {
    auto iter = create_tablet_iterator(tablet, version);
    if (iter == nullptr) {
        return -1;
    }
    auto full_chunk = vectorized::ChunkHelper::new_chunk(iter->schema(), keys.size());
    auto& cols = full_chunk->columns();
    for (size_t i = 0; i < keys.size(); i++) {
        cols[0]->append_datum(vectorized::Datum((int32_t)keys[i]));
        cols[1]->append_datum(vectorized::Datum((int16_t)(keys[i] % 100 + 1)));
        cols[2]->append_datum(vectorized::Datum(Slice{std::to_string((int64_t)(keys[i] % 1000 + 2))}));
        cols[3]->append_datum(vectorized::Datum(1));
    }
    auto chunk = vectorized::ChunkHelper::new_chunk(iter->schema(), 100);
    size_t count = 0;
    while (true) {
        auto st = iter->get_next(chunk.get());
        if (st.is_end_of_file()) {
            break;
        } else if (st.ok()) {
            for (auto i = 0; i < chunk->num_rows(); i++) {
                EXPECT_EQ(full_chunk->get(count + i).compare(iter->schema(), chunk->get(i)), 0);
            }
            count += chunk->num_rows();
            chunk->reset();
        } else {
            return -1;
        }
    }
    return count;
}

TEST_F(TabletUpdatesTest, writeread) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    // write
    const int N = 8000;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    auto rs0 = create_rowset(_tablet, keys);
    ASSERT_TRUE(_tablet->rowset_commit(2, rs0).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());
    auto rs1 = create_rowset(_tablet, keys);
    ASSERT_TRUE(_tablet->rowset_commit(3, rs1).ok());
    ASSERT_EQ(3, _tablet->updates()->max_version());
    // read
    ASSERT_EQ(N, read_tablet(_tablet, 3));
    ASSERT_EQ(N, read_tablet(_tablet, 2));
}

TEST_F(TabletUpdatesTest, writeread_with_delete) {
    _tablet = create_tablet(rand(), rand());
    // write
    const int N = 8000;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    // Insert [0, 1, 2 ... N)
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    // Delete [0, 1, 2 ... N/2)
    vectorized::Int64Column deletes;
    deletes.append_numbers(keys.data(), sizeof(int64_t) * keys.size() / 2);
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, {}, &deletes)).ok());
    ASSERT_EQ(3, _tablet->updates()->max_version());
    ASSERT_EQ(N / 2, read_tablet(_tablet, 3));

    // Delete [0, 1, 2 ... N) and insert [N, N+1, N+2 ... 2*N)
    deletes.resize(0);
    deletes.append_numbers(keys.data(), sizeof(int64_t) * keys.size());
    for (int i = 0; i < N; i++) {
        keys[i] = N + i;
    }
    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys, &deletes)).ok());
    ASSERT_EQ(4, _tablet->updates()->max_version());
    ASSERT_EQ(N, read_tablet(_tablet, 4));
}

TEST_F(TabletUpdatesTest, noncontinous_commit) {
    _tablet = create_tablet(rand(), rand());
    const int N = 100;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    ASSERT_TRUE(_tablet->rowset_commit(5, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(5, _tablet->updates()->max_version());
}

TEST_F(TabletUpdatesTest, noncontinous_meta_save_load) {
    _tablet = create_tablet(rand(), rand());
    const int N = 100;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    ASSERT_TRUE(_tablet->rowset_commit(5, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(2, _tablet->updates()->max_version());

    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    _tablet->save_meta();

    auto tablet1 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), _tablet);

    ASSERT_EQ(2, tablet1->updates()->num_pending());
    ASSERT_EQ(2, tablet1->updates()->max_version());
}

TEST_F(TabletUpdatesTest, save_meta) {
    _tablet = create_tablet(rand(), rand());

    // Prepare records for test.
    const int N = 10;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    for (int i = 0; i < 30; i++) {
        std::cout << "rowset " << i << std::endl;
        ASSERT_TRUE(_tablet->rowset_commit(i + 2, create_rowset(_tablet, keys)).ok());
    }
    ASSERT_EQ(31, _tablet->updates()->version_history_count());
    ASSERT_EQ(31, _tablet->updates()->max_version());

    // Read from the latest version, this can ensure that all versions are applied.
    ASSERT_EQ(N, read_tablet(_tablet, 31));
    ASSERT_EQ(N, read_tablet(_tablet, 16));
    ASSERT_EQ(N, read_tablet(_tablet, 2));

    _tablet->save_meta();

    auto tablet1 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), _tablet);
    ASSERT_EQ(31, tablet1->updates()->version_history_count());
    ASSERT_EQ(31, tablet1->updates()->max_version());

    // Ensure that all meta logs have been erased.
    size_t log_count = 0;
    auto apply_log_func = [&](uint64_t logid, const TabletMetaLogPB& log) -> bool {
        log_count++;
        std::cout << log.DebugString() << std::endl;
        return true;
    };
    auto status = TabletMetaManager::traverse_meta_logs(_tablet->data_dir(), _tablet->tablet_id(), apply_log_func);
    ASSERT_TRUE(status.ok()) << status.to_string();
    ASSERT_EQ(0, log_count);

    // Ensure we can read all records from the latest version.
    ASSERT_EQ(N, read_tablet(tablet1, 30));
    ASSERT_EQ(N, read_tablet(tablet1, 10));
    ASSERT_EQ(N, read_tablet(tablet1, 2));
}

TEST_F(TabletUpdatesTest, remove_expired_versions) {
    _tablet = create_tablet(rand(), rand());

    // Prepare records for test.
    const int N = 100;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, keys)).ok());
    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());
    ASSERT_EQ(4, _tablet->updates()->version_history_count());
    ASSERT_EQ(4, _tablet->updates()->max_version());

    // Read from the latest version, this can ensure that all versions are applied.
    ASSERT_EQ(N, read_tablet(_tablet, 4));
    ASSERT_EQ(N, read_tablet(_tablet, 3));
    ASSERT_EQ(N, read_tablet(_tablet, 2));
    ASSERT_EQ(0, read_tablet(_tablet, 1));

    // Create iterators before remove expired version, but read them after removal.
    auto iter_v0 = create_tablet_iterator(_tablet, 1);
    auto iter_v1 = create_tablet_iterator(_tablet, 2);
    auto iter_v2 = create_tablet_iterator(_tablet, 3);
    auto iter_v3 = create_tablet_iterator(_tablet, 4);

    // Remove all but the last version.
    _tablet->updates()->remove_expired_versions(time(NULL));
    ASSERT_EQ(1, _tablet->updates()->version_history_count());
    ASSERT_EQ(4, _tablet->updates()->max_version());

    EXPECT_EQ(N, read_tablet(_tablet, 4));
    EXPECT_EQ(N, read_until_eof(iter_v3));
    EXPECT_EQ(N, read_until_eof(iter_v2)); // delete vector v2 still valid.
    EXPECT_EQ(0, read_until_eof(iter_v0)); // iter_v0 is empty iterator

    // Read expired versions should fail.
    EXPECT_EQ(-1, read_until_eof(iter_v1));
    EXPECT_EQ(-1, read_tablet(_tablet, 3));
    EXPECT_EQ(-1, read_tablet(_tablet, 2));
    EXPECT_EQ(-1, read_tablet(_tablet, 1));

    auto tablet1 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), _tablet);
    EXPECT_EQ(1, tablet1->updates()->version_history_count());
    EXPECT_EQ(4, tablet1->updates()->max_version());
    EXPECT_EQ(N, read_tablet(tablet1, 4));
    EXPECT_EQ(-1, read_tablet(tablet1, 3));
    EXPECT_EQ(-1, read_tablet(tablet1, 2));
    EXPECT_EQ(-1, read_tablet(tablet1, 1));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, apply) {
    const int N = 10;
    _tablet = create_tablet(rand(), rand());
    ASSERT_EQ(1, _tablet->updates()->version_history_count());

    std::vector<int64_t> keys(N);
    for (int i = 0; i < N; i++) {
        keys[i] = i;
    }
    std::vector<RowsetSharedPtr> rowsets;
    rowsets.reserve(64);
    for (int i = 0; i < 64; i++) {
        rowsets.emplace_back(create_rowset(_tablet, keys));
    }
    auto pool = StorageEngine::instance()->update_manager()->apply_thread_pool();
    for (int i = 0; i < rowsets.size(); i++) {
        auto version = i + 2;
        auto st = _tablet->rowset_commit(version, rowsets[i]);
        ASSERT_TRUE(st.ok()) << st.to_string();
        // Ensure that there is at most one thread doing the version apply job.
        ASSERT_LE(pool->num_threads(), 1);
        ASSERT_EQ(version, _tablet->updates()->max_version());
        ASSERT_EQ(version, _tablet->updates()->version_history_count());
    }
    ASSERT_EQ(N, read_tablet(_tablet, rowsets.size()));

    // Ensure the persistent meta is correct.
    auto max_version = rowsets.size() + 1;
    auto tablet1 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), _tablet);
    EXPECT_EQ(max_version, tablet1->updates()->max_version());
    EXPECT_EQ(max_version, tablet1->updates()->version_history_count());
    for (int i = 2; i <= max_version; i++) {
        ASSERT_EQ(N, read_tablet(_tablet, i));
    }
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, concurrent_write_read_and_gc) {
    const int N = 2000;
    std::atomic<bool> started{false};
    std::atomic<bool> stopped{false};
    std::atomic<int64_t> version{1};
    _tablet = create_tablet(rand(), rand());

    auto wait_start = [&]() {
        while (!started) {
            sched_yield();
        }
    };

    auto rowset_commit_thread = [&]() {
        std::vector<int64_t> keys(N);
        for (int i = 0; i < N; i++) {
            keys[i] = i;
        }
        wait_start();
        while (!stopped) {
            ASSERT_TRUE(_tablet->rowset_commit(1 + version.load(), create_rowset(_tablet, keys)).ok());
            version.fetch_add(1);
        }
    };

    auto version_gc_thread = [&]() {
        wait_start();
        while (!stopped) {
            _tablet->updates()->remove_expired_versions(time(NULL));
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    };

    auto read_thread = [&]() {
        wait_start();
        while (!stopped) {
            ssize_t ret = read_tablet(_tablet, std::max<int64_t>(2, version.load()));
            ASSERT_TRUE(ret == -1 || ret == N) << ret;
        }
    };

    std::vector<std::thread> threads;
    threads.emplace_back(rowset_commit_thread);
    for (int i = 0; i < 10; i++) {
        threads.emplace_back(read_thread);
    }
    threads.emplace_back(version_gc_thread);
    started.store(true);
    std::this_thread::sleep_for(std::chrono::seconds(5));
    while (version.load() < 100) {
        std::this_thread::sleep_for(std::chrono::seconds(5));
    }
    stopped.store(true);
    for (auto& t : threads) {
        t.join();
    }
    std::cout << "version count=" << version.load() << std::endl;
    EXPECT_EQ(N, read_tablet(_tablet, version.load()));
    _tablet->updates()->remove_expired_versions(time(NULL));
    EXPECT_EQ(1, _tablet->updates()->version_history_count());
    EXPECT_EQ(version.load(), _tablet->updates()->max_version());

    // Ensure the persistent meta is correct.
    auto tablet1 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), _tablet);
    EXPECT_EQ(1, tablet1->updates()->version_history_count());
    EXPECT_EQ(version.load(), tablet1->updates()->max_version());
    EXPECT_EQ(N, read_tablet(tablet1, version.load()));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, compaction_score_not_enough) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    std::vector<int64_t> keys;
    for (int i = 0; i < 100; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    const auto& best_tablet =
            StorageEngine::instance()->tablet_manager()->find_best_tablet_to_do_update_compaction(_tablet->data_dir());
    EXPECT_EQ(best_tablet, nullptr);
    // the compaction score is not enough due to the enough rows and lacking deletion.
    EXPECT_LT(_tablet->updates()->get_compaction_score(), 0);
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, compaction_score_enough_duplicate) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    std::vector<int64_t> keys;
    for (int i = 0; i < 100; i++) {
        keys.push_back(i);
    }
    // Delete [0, 1, 2 ... 86)
    vectorized::Int64Column deletes;
    deletes.append_numbers(keys.data(), sizeof(int64_t) * 86);
    // This (keys and deletes has duplicate keys) is illegal and won't happen in real world
    // but currently underlying implementation still support this, so we test this case anyway
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys, &deletes)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    const auto& best_tablet =
            StorageEngine::instance()->tablet_manager()->find_best_tablet_to_do_update_compaction(_tablet->data_dir());
    EXPECT_NE(best_tablet, nullptr);
    // the compaction score is enough due to the enough deletion.
    EXPECT_GT(_tablet->updates()->get_compaction_score(), 0);
}

TEST_F(TabletUpdatesTest, compaction_score_enough_normal) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    std::vector<int64_t> keys;
    for (int i = 0; i < 100; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    // Delete [0, 1, 2 ... 86)
    vectorized::Int64Column deletes;
    deletes.append_numbers(keys.data(), sizeof(int64_t) * 86);
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, {}, &deletes)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    const auto& best_tablet =
            StorageEngine::instance()->tablet_manager()->find_best_tablet_to_do_update_compaction(_tablet->data_dir());
    EXPECT_NE(best_tablet, nullptr);
    // the compaction score is enough due to the enough deletion.
    EXPECT_GT(_tablet->updates()->get_compaction_score(), 0);
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, horizontal_compaction) {
    config::vertical_compaction_max_columns_per_group = 5;

    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    std::vector<int64_t> keys;
    for (int i = 0; i < 100; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    ASSERT_EQ(_tablet->updates()->version_history_count(), 4);
    const auto& best_tablet =
            StorageEngine::instance()->tablet_manager()->find_best_tablet_to_do_update_compaction(_tablet->data_dir());
    EXPECT_EQ(best_tablet->tablet_id(), _tablet->tablet_id());
    EXPECT_GT(best_tablet->updates()->get_compaction_score(), 0);
    ASSERT_TRUE(best_tablet->updates()->compaction(_compaction_mem_tracker.get()).ok());
    std::this_thread::sleep_for(std::chrono::seconds(1));
    EXPECT_EQ(100, read_tablet_and_compare(best_tablet, 3, keys));
    ASSERT_EQ(best_tablet->updates()->num_rowsets(), 1);
    ASSERT_EQ(best_tablet->updates()->version_history_count(), 5);
    // the time interval is not enough after last compaction
    EXPECT_EQ(best_tablet->updates()->get_compaction_score(), -1);
}

TEST_F(TabletUpdatesTest, vertical_compaction) {
    config::vertical_compaction_max_columns_per_group = 1;

    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    std::vector<int64_t> keys;
    for (int i = 0; i < 100; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    ASSERT_EQ(_tablet->updates()->version_history_count(), 4);
    const auto& best_tablet =
            StorageEngine::instance()->tablet_manager()->find_best_tablet_to_do_update_compaction(_tablet->data_dir());
    EXPECT_EQ(best_tablet->tablet_id(), _tablet->tablet_id());
    EXPECT_GT(best_tablet->updates()->get_compaction_score(), 0);
    ASSERT_TRUE(best_tablet->updates()->compaction(_compaction_mem_tracker.get()).ok());
    std::this_thread::sleep_for(std::chrono::seconds(1));
    EXPECT_EQ(100, read_tablet_and_compare(best_tablet, 3, keys));
    ASSERT_EQ(best_tablet->updates()->num_rowsets(), 1);
    ASSERT_EQ(best_tablet->updates()->version_history_count(), 5);
    // the time interval is not enough after last compaction
    EXPECT_EQ(best_tablet->updates()->get_compaction_score(), -1);
}

TEST_F(TabletUpdatesTest, link_from) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    _tablet2 = create_tablet2(rand(), rand());
    std::vector<int64_t> keys;
    int N = 100;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    _tablet2->set_tablet_state(TABLET_NOTREADY);
    ASSERT_TRUE(_tablet2->updates()->link_from(_tablet.get(), 4).ok());

    ASSERT_EQ(N, read_tablet(_tablet2, 4));
}

TEST_F(TabletUpdatesTest, convert_from) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    const auto& tablet_to_schema_change = create_tablet_to_schema_change(rand(), rand());
    std::vector<int64_t> keys;
    int N = 100;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowset(_tablet, keys)).ok());
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowset(_tablet, keys)).ok());
    ASSERT_TRUE(_tablet->rowset_commit(4, create_rowset(_tablet, keys)).ok());

    tablet_to_schema_change->set_tablet_state(TABLET_NOTREADY);
    auto chunk_changer = std::make_unique<vectorized::ChunkChanger>(tablet_to_schema_change->tablet_schema());
    for (int i = 0; i < tablet_to_schema_change->tablet_schema().num_columns(); ++i) {
        const auto& new_column = tablet_to_schema_change->tablet_schema().column(i);
        int32_t column_index = _tablet->field_index(std::string{new_column.name()});
        auto column_mapping = chunk_changer->get_mutable_column_mapping(i);
        if (column_index >= 0) {
            column_mapping->ref_column = column_index;
        } else {
            column_mapping->default_value = WrapperField::create(new_column);

            ASSERT_FALSE(column_mapping->default_value == nullptr) << "init column mapping failed: malloc error";

            if (new_column.is_nullable() && new_column.default_value().length() == 0) {
                column_mapping->default_value->set_null();
            } else {
                column_mapping->default_value->from_string(new_column.default_value());
            }
        }
    }
    ASSERT_TRUE(tablet_to_schema_change->updates()->convert_from(_tablet, 4, chunk_changer.get()).ok());

    ASSERT_EQ(N, read_tablet_and_compare_schema_changed(tablet_to_schema_change, 4, keys));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_incremental) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2 /*version*/, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2 /*version*/, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_incremental(tablet0, {4, 5, 6}, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    for (const auto& f : files) {
        std::string src = meta_dir + "/" + f;
        std::string dst = tablet1->schema_hash_path() + "/" + f;
        st = Env::Default()->link_file(src, dst);
        ASSERT_TRUE(st.ok()) << st;
        LOG(INFO) << "Linked " << src << " to " << dst;
    }
    // Pretend that tablet0 is a peer replica of tablet1
    snapshot_meta->tablet_meta().set_tablet_id(tablet1->tablet_id());
    snapshot_meta->tablet_meta().set_schema_hash(tablet1->schema_hash());
    for (auto& rm : snapshot_meta->rowset_metas()) {
        rm.set_tablet_id(tablet1->tablet_id());
    }

    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_TRUE(st.ok()) << st;
    ASSERT_EQ(6, tablet1->updates()->max_version());
    ASSERT_EQ(6, tablet1->updates()->version_history_count());
    EXPECT_EQ(10, read_tablet(tablet1, 6));

    auto tablet2 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), tablet1);
    ASSERT_EQ(6, tablet2->updates()->max_version());
    ASSERT_EQ(6, tablet2->updates()->version_history_count());
    EXPECT_EQ(10, read_tablet(tablet2, 6));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_incremental_ignore_already_committed_version) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2 /*version*/, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2 /*version*/, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_incremental(tablet0, {2, 3, 4, 5, 6}, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    for (const auto& f : files) {
        std::string src = meta_dir + "/" + f;
        std::string dst = tablet1->schema_hash_path() + "/" + f;
        st = Env::Default()->link_file(src, dst);
        ASSERT_TRUE(st.ok()) << st;
        LOG(INFO) << "Linked " << src << " to " << dst;
    }
    // Pretend that tablet0 is a peer replica of tablet1
    snapshot_meta->tablet_meta().set_tablet_id(tablet1->tablet_id());
    snapshot_meta->tablet_meta().set_schema_hash(tablet1->schema_hash());
    for (auto& rm : snapshot_meta->rowset_metas()) {
        rm.set_tablet_id(tablet1->tablet_id());
    }

    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_TRUE(st.ok()) << st;
    ASSERT_EQ(6, tablet1->updates()->max_version());
    ASSERT_EQ(6, tablet1->updates()->version_history_count());
    EXPECT_EQ(10, read_tablet(tablet1, 6));

    auto tablet2 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), tablet1);
    ASSERT_EQ(6, tablet2->updates()->max_version());
    ASSERT_EQ(6, tablet2->updates()->version_history_count());
    EXPECT_EQ(10, read_tablet(tablet2, 6));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_incremental_mismatched_tablet_id) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2 /*version*/, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2 /*version*/, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_incremental(tablet0, {4, 5, 6}, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    for (const auto& f : files) {
        std::string src = meta_dir + "/" + f;
        std::string dst = tablet1->schema_hash_path() + "/" + f;
        st = Env::Default()->link_file(src, dst);
        ASSERT_TRUE(st.ok()) << st;
        LOG(INFO) << "Linked " << src << " to " << dst;
    }

    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_FALSE(st.ok());
    ASSERT_TRUE(st.to_string().find("mismatched tablet id") != std::string::npos);
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_incremental_data_file_not_exist) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2 /*version*/, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2 /*version*/, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_incremental(tablet0, {4, 5, 6}, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    // Pretend that tablet0 is a peer replica of tablet1
    snapshot_meta->tablet_meta().set_tablet_id(tablet1->tablet_id());
    snapshot_meta->tablet_meta().set_schema_hash(tablet1->schema_hash());
    for (auto& rm : snapshot_meta->rowset_metas()) {
        rm.set_tablet_id(tablet1->tablet_id());
    }

    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_FALSE(st.ok());
    ASSERT_TRUE(st.to_string().find("segment file does not exist") != std::string::npos);
    ASSERT_EQ(3, tablet1->updates()->max_version());
    ASSERT_EQ(3, tablet1->updates()->version_history_count());
    EXPECT_EQ(4, read_tablet(tablet1, tablet1->updates()->max_version()));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_incremental_incorrect_version) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2 /*version*/, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2 /*version*/, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_incremental(tablet0, {5, 6}, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    for (const auto& f : files) {
        std::string src = meta_dir + "/" + f;
        std::string dst = tablet1->schema_hash_path() + "/" + f;
        st = Env::Default()->link_file(src, dst);
        ASSERT_TRUE(st.ok()) << st;
        LOG(INFO) << "Linked " << src << " to " << dst;
    }
    // Pretend that tablet0 is a peer replica of tablet1
    snapshot_meta->tablet_meta().set_tablet_id(tablet1->tablet_id());
    snapshot_meta->tablet_meta().set_schema_hash(tablet1->schema_hash());
    for (auto& rm : snapshot_meta->rowset_metas()) {
        rm.set_tablet_id(tablet1->tablet_id());
    }

    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_TRUE(st.ok()) << st;
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_full) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2, create_rowset(tablet1, keys1)).ok());
    }

    auto st = full_clone(tablet0, 11, tablet1);
    ASSERT_TRUE(st.ok()) << st;
    ASSERT_EQ(11, tablet1->updates()->max_version());
    ASSERT_EQ(1, tablet1->updates()->version_history_count());
    EXPECT_EQ(keys0.size(), read_tablet(tablet1, tablet1->updates()->max_version()));

    // Ensure that the tablet state is valid after process restarted.
    auto tablet2 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), tablet1);
    ASSERT_EQ(11, tablet2->updates()->max_version());
    ASSERT_EQ(1, tablet2->updates()->version_history_count());
    EXPECT_EQ(keys0.size(), read_tablet(tablet2, tablet2->updates()->max_version()));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_full_file_not_exist) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_full(tablet0, 11, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    // Pretend that tablet0 is a peer replica of tablet1
    snapshot_meta->tablet_meta().set_tablet_id(tablet1->tablet_id());
    snapshot_meta->tablet_meta().set_schema_hash(tablet1->schema_hash());
    for (auto& rm : snapshot_meta->rowset_metas()) {
        rm.set_tablet_id(tablet1->tablet_id());
    }

    // Segment files does not link to the directory of tablet1.
    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_FALSE(st.ok());
    ASSERT_TRUE(st.to_string().find("segment file does not exist") != std::string::npos);
    ASSERT_EQ(3, tablet1->updates()->max_version());
    ASSERT_EQ(3, tablet1->updates()->version_history_count());
    EXPECT_EQ(keys1.size(), read_tablet(tablet1, tablet1->updates()->max_version()));

    // Ensure that the persistent meta is still valid.
    auto tablet2 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), tablet1);
    ASSERT_EQ(3, tablet2->updates()->max_version());
    ASSERT_EQ(3, tablet2->updates()->version_history_count());
    EXPECT_EQ(keys1.size(), read_tablet(tablet2, tablet2->updates()->max_version()));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, load_snapshot_full_mismatched_tablet_id) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2, create_rowset(tablet1, keys1)).ok());
    }

    auto snapshot_dir = SnapshotManager::instance()->snapshot_full(tablet0, 11, 3600);
    ASSERT_TRUE(snapshot_dir.ok()) << snapshot_dir.status();

    DeferOp defer1([&]() { (void)FileUtils::remove_all(*snapshot_dir); });

    auto meta_dir = SnapshotManager::instance()->get_schema_hash_full_path(tablet0, *snapshot_dir);
    auto snapshot_meta = SnapshotManager::instance()->parse_snapshot_meta(meta_dir + "/meta");
    ASSERT_TRUE(snapshot_meta.ok()) << snapshot_meta.status();

    std::set<std::string> files;
    auto st = FileUtils::list_dirs_files(meta_dir, NULL, &files);
    ASSERT_TRUE(st.ok()) << st;
    files.erase("meta");

    for (const auto& f : files) {
        std::string src = meta_dir + "/" + f;
        std::string dst = tablet1->schema_hash_path() + "/" + f;
        st = Env::Default()->link_file(src, dst);
        ASSERT_TRUE(st.ok()) << st;
        LOG(INFO) << "Linked " << src << " to " << dst;
    }

    // tablet_id and schema_hash does not match.
    st = tablet1->updates()->load_snapshot(*snapshot_meta);
    ASSERT_FALSE(st.ok());
    ASSERT_TRUE(st.to_string().find("mismatched tablet id") != std::string::npos);
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, test_issue_4193) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    // commit tablet0 to version 11
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    // commit tablet1 to version 3
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2, create_rowset(tablet1, keys1)).ok());
    }
    keys1 = {10, 11, 12};
    // commit tablet1 extra two pending rowsets
    ASSERT_TRUE(tablet1->rowset_commit(12, create_rowset(tablet1, keys1)).ok());
    ASSERT_TRUE(tablet1->rowset_commit(13, create_rowset(tablet1, keys1)).ok());

    auto st = full_clone(tablet0, 11, tablet1);
    ASSERT_TRUE(st.ok()) << st;
    ASSERT_EQ(13, tablet1->updates()->max_version());
    EXPECT_EQ(keys0.size() + keys1.size(), read_tablet(tablet1, tablet1->updates()->max_version()));

    // Ensure that the tablet state is valid after process restarted.
    auto tablet2 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), tablet1);
    ASSERT_EQ(13, tablet2->updates()->max_version());
    EXPECT_EQ(keys0.size() + keys1.size(), read_tablet(tablet2, tablet2->updates()->max_version()));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, test_issue_4181) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());
    auto tablet1 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    // commit tablet0 to version 11
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2, create_rowset(tablet0, keys0)).ok());
    }

    std::vector<int64_t> keys1{0, 1, 2, 3};
    // commit tablet1 to version 3
    for (int i = 0; i < 2; i++) {
        ASSERT_TRUE(tablet1->rowset_commit(i + 2, create_rowset(tablet1, keys1)).ok());
    }

    auto st = full_clone(tablet0, 9, tablet1);
    ASSERT_TRUE(st.ok()) << st;

    st = full_clone(tablet0, 10, tablet1);
    ASSERT_TRUE(st.ok()) << st;

    st = full_clone(tablet0, 11, tablet1);

    ASSERT_EQ(11, tablet1->updates()->max_version());
    EXPECT_EQ(keys0.size(), read_tablet(tablet1, tablet1->updates()->max_version()));

    // Ensure that the tablet state is valid after process restarted.
    auto tablet2 = load_same_tablet_from_store(_tablet_meta_mem_tracker.get(), tablet1);
    ASSERT_EQ(11, tablet2->updates()->max_version());
    EXPECT_EQ(keys0.size(), read_tablet(tablet2, tablet2->updates()->max_version()));
}

// NOLINTNEXTLINE
TEST_F(TabletUpdatesTest, snapshot_with_empty_rowset) {
    srand(GetCurrentTimeMicros());
    auto tablet0 = create_tablet(rand(), rand());

    DeferOp defer([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet0->tablet_id());
        (void)FileUtils::remove_all(tablet0->schema_hash_path());
    });

    std::vector<int64_t> keys0{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
        ASSERT_TRUE(tablet0->rowset_commit(i + 2, create_rowset(tablet0, keys0)).ok());
    }
    // Empty rowset.
    ASSERT_TRUE(tablet0->rowset_commit(12, create_rowset(tablet0, std::vector<int64_t>{})).ok());

    auto res = clone_a_new_replica(tablet0, rand());
    ASSERT_TRUE(res.ok()) << res.status();
    ASSERT_TRUE(*res != nullptr);
    auto tablet1 = std::move(res).value();

    DeferOp defer2([&]() {
        auto tablet_mgr = StorageEngine::instance()->tablet_manager();
        (void)tablet_mgr->drop_tablet(tablet1->tablet_id());
        (void)FileUtils::remove_all(tablet1->schema_hash_path());
    });

    ASSERT_EQ(12, tablet1->updates()->max_version());
    ASSERT_EQ(1, tablet1->updates()->version_history_count());

    MemTracker tracker;
    Status st = tablet1->updates()->compaction(&tracker);
    ASSERT_TRUE(st.ok()) << st;

    // Wait until compaction applied.
    while (true) {
        std::vector<RowsetSharedPtr> rowsets;
        EditVersion full_version;
        ASSERT_TRUE(tablet1->updates()->get_applied_rowsets(12, &rowsets, &full_version).ok());
        if (full_version.minor() == 1) {
            break;
        }
        std::cerr << "waiting for compaction applied\n";
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    ASSERT_EQ(12, tablet1->updates()->max_version());
    EXPECT_EQ(keys0.size(), read_tablet(tablet1, tablet1->updates()->max_version()));
}

TEST_F(TabletUpdatesTest, get_column_values) {
    srand(GetCurrentTimeMicros());
    _tablet = create_tablet(rand(), rand());
    const int N = 8000;
    std::vector<int64_t> keys;
    for (int i = 0; i < N; i++) {
        keys.push_back(i);
    }
    std::size_t max_rows_per_segment = 1000;
    ASSERT_TRUE(_tablet->rowset_commit(2, create_rowsets(_tablet, keys, max_rows_per_segment)).ok());
    ASSERT_TRUE(_tablet->rowset_commit(3, create_rowsets(_tablet, keys, max_rows_per_segment)).ok());
    std::vector<uint32_t> read_column_ids = {1, 2};
    std::vector<std::unique_ptr<vectorized::Column>> read_columns(read_column_ids.size());
    const auto& tablet_schema = _tablet->tablet_schema();
    for (auto i = 0; i < read_column_ids.size(); i++) {
        const auto read_column_id = read_column_ids[i];
        auto tablet_column = tablet_schema.column(read_column_id);
        auto column =
                vectorized::ChunkHelper::column_from_field_type(tablet_column.type(), tablet_column.is_nullable());
        read_columns[i] = column->clone_empty();
    }
    std::map<uint32_t, std::vector<uint32_t>> rowids_by_rssid;
    int num_segments = N / max_rows_per_segment;
    for (auto i = 0; i < num_segments; i++) {
        const int num_rowids = rand() % max_rows_per_segment;
        std::vector<uint32_t> rowids;
        for (auto i = 0; i < num_rowids; i++) {
            rowids.push_back(rand() % max_rows_per_segment);
        }
        std::sort(rowids.begin(), rowids.end());
        rowids_by_rssid.emplace(i, rowids);
    }
    _tablet->updates()->get_column_values(read_column_ids, false, rowids_by_rssid, &read_columns);
    auto values_str_generator = [&rowids_by_rssid](const int modulus, const int base) {
        std::stringstream ss;
        ss << "[";
        for (const auto& [rssid, rowids] : rowids_by_rssid) {
            for (const auto rowid : rowids) {
                ss << rowid % modulus + base << ", ";
            }
        }
        std::string values_str = ss.str();
        values_str.pop_back();
        values_str.pop_back();
        values_str.append("]");
        return values_str;
    };
    ASSERT_EQ(values_str_generator(100, 1), read_columns[0]->debug_string());
    ASSERT_EQ(values_str_generator(1000, 2), read_columns[1]->debug_string());
    for (const auto& read_column : read_columns) {
        read_column->reset_column();
    }
    _tablet->updates()->get_column_values(read_column_ids, true, rowids_by_rssid, &read_columns);
    ASSERT_EQ(std::string("[0, ") + values_str_generator(100, 1).substr(1), read_columns[0]->debug_string());
    ASSERT_EQ(std::string("[0, ") + values_str_generator(1000, 2).substr(1), read_columns[1]->debug_string());
}

} // namespace starrocks
