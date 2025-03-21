// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#pragma once

#include "exec/pipeline/operator.h"
#include "exec/pipeline/set/except_context.h"

namespace starrocks::pipeline {

// ExceptNode is decomposed to ExceptBuildSinkOperator, ExceptProbeSinkOperator, and ExceptOutputSourceOperator.
// - ExceptBuildSinkOperator builds a hast set from the ExceptNode's first child.
// - Each ExceptProbeSinkOperator probes the hash set built by ExceptBuildSinkOperator and labels the key as deleted.
// - ExceptOutputSourceOperator traverses the hast set and picks up undeleted entries after probe phase is finished.
//
// ExceptBuildSinkOperator, ExceptProbeSinkOperator, and ExceptOutputSourceOperator
// belong to different pipelines. There is dependency between them:
// - The first ExceptProbeSinkOperator depends on ExceptBuildSinkOperator.
// - The rest ExceptProbeSinkOperator depends on the prev ExceptProbeSinkOperator.
// - ExceptOutputSourceOperator depends on the last ExceptProbeSinkOperator.
// The execution sequence is as follows: ExceptBuildSinkOperator -> ExceptProbeSinkOperator 0
// -> ExceptProbeSinkOperator 1 -> ... -> ExceptProbeSinkOperator N -> ExceptBuildSinkOperator.
//
// The rows are shuffled to degree of parallelism (DOP) partitions by local shuffle exchange.
// For each partition, there are a ExceptBuildSinkOperator driver, a ExceptProbeSinkOperator driver
// for each child, and a ExceptOutputSourceOperator.
class ExceptBuildSinkOperator final : public Operator {
public:
    ExceptBuildSinkOperator(OperatorFactory* factory, int32_t id, int32_t plan_node_id,
                            std::shared_ptr<ExceptContext> except_ctx, const std::vector<ExprContext*>& dst_exprs)
            : Operator(factory, id, "except_build_sink", plan_node_id),
              _except_ctx(std::move(except_ctx)),
              _dst_exprs(dst_exprs) {
        _except_ctx->ref();
    }

    bool need_input() const override { return !is_finished(); }

    bool has_output() const override { return false; }

    bool is_finished() const override { return _is_finished || _except_ctx->is_finished(); }

    Status set_finishing(RuntimeState* state) override {
        _is_finished = true;
        _except_ctx->finish_build_ht();
        return Status::OK();
    }

    Status prepare(RuntimeState* state) override;
    void close(RuntimeState* state) override;

    StatusOr<vectorized::ChunkPtr> pull_chunk(RuntimeState* state) override;

    Status push_chunk(RuntimeState* state, const vectorized::ChunkPtr& chunk) override;

private:
    std::shared_ptr<ExceptContext> _except_ctx;

    const std::vector<ExprContext*>& _dst_exprs;

    bool _is_finished = false;
};

class ExceptBuildSinkOperatorFactory final : public OperatorFactory {
public:
    ExceptBuildSinkOperatorFactory(int32_t id, int32_t plan_node_id,
                                   ExceptPartitionContextFactoryPtr except_partition_ctx_factory,
                                   const std::vector<ExprContext*>& dst_exprs)
            : OperatorFactory(id, "except_build_sink", plan_node_id),
              _except_partition_ctx_factory(std::move(except_partition_ctx_factory)),
              _dst_exprs(dst_exprs) {}

    OperatorPtr create(int32_t degree_of_parallelism, int32_t driver_sequence) override {
        return std::make_shared<ExceptBuildSinkOperator>(
                this, _id, _plan_node_id, _except_partition_ctx_factory->get_or_create(driver_sequence), _dst_exprs);
    }

    Status prepare(RuntimeState* state) override;

    void close(RuntimeState* state) override;

private:
    ExceptPartitionContextFactoryPtr _except_partition_ctx_factory;

    const std::vector<ExprContext*>& _dst_exprs;
};

} // namespace starrocks::pipeline
