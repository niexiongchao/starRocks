// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.CTEContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalCTEProduceOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalLimitOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarRangePredicateExtractor;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/*
 *                    CTEProduce
 *                         |
 *  CTEProduce           Limit
 *      |         =>       |
 *     Node              Filter
 *                         |
 *                        Node
 *
 * */
public class PushLimitAndFilterToCTEProduceRule extends TransformationRule {
    public PushLimitAndFilterToCTEProduceRule() {
        super(RuleType.TF_PUSH_CTE_PRODUCE,
                Pattern.create(OperatorType.LOGICAL_CTE_PRODUCE, OperatorType.PATTERN_LEAF));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        CTEContext cteContext = context.getCteContext();
        LogicalCTEProduceOperator produce = (LogicalCTEProduceOperator) input.getOp();

        List<Long> limits = cteContext.getConsumeLimits().getOrDefault(produce.getCteId(), Collections.emptyList());
        List<ScalarOperator> predicates =
                cteContext.getConsumePredicates().getOrDefault(produce.getCteId(), Collections.emptyList());

        int consumeNums = cteContext.getCTEConsumeNums(produce.getCteId());

        return limits.size() == consumeNums || predicates.size() == consumeNums;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        CTEContext cteContext = context.getCteContext();
        LogicalCTEProduceOperator produce = (LogicalCTEProduceOperator) input.getOp();

        List<Long> limits = cteContext.getConsumeLimits().getOrDefault(produce.getCteId(), Collections.emptyList());
        List<ScalarOperator> predicates =
                cteContext.getConsumePredicates().getOrDefault(produce.getCteId(), Collections.emptyList());

        int consumeNums = cteContext.getCTEConsumeNums(produce.getCteId());

        OptExpression child = input.getInputs().get(0);
        if (consumeNums == predicates.size()) {
            ScalarOperator orPredicate = Utils.compoundOr(Lists.newArrayList(new LinkedHashSet<>(predicates)));
            ScalarRangePredicateExtractor extractor = new ScalarRangePredicateExtractor();
            child = OptExpression.create(new LogicalFilterOperator(extractor.rewriteAll(orPredicate)), child);
        }

        if (consumeNums == limits.size()) {
            Long maxLimit = limits.stream().reduce(Long::max).orElse(Operator.DEFAULT_LIMIT);
            child = OptExpression.create(new LogicalLimitOperator(maxLimit), child);
        }

        return Lists.newArrayList(OptExpression.create(produce, child));
    }
}
