// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.optimizer.statistics;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperatorVisitor;
import org.apache.commons.math3.util.Precision;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

public class PredicateStatisticsCalculator {
    public static Statistics statisticsCalculate(ScalarOperator predicate, Statistics statistics) {
        if (predicate == null) {
            return statistics;
        }
        return predicate.accept(new PredicateStatisticsCalculatingVisitor(statistics), null);
    }

    private static class PredicateStatisticsCalculatingVisitor extends ScalarOperatorVisitor<Statistics, Void> {
        private final Statistics statistics;

        public PredicateStatisticsCalculatingVisitor(Statistics statistics) {
            this.statistics = statistics;
        }

        private boolean checkNeedEvalEstimate(ScalarOperator predicate) {
            if (predicate == null) {
                return false;
            }
            // check predicate need to eval
            if (predicate.isNotEvalEstimate()) {
                return false;
            }
            // extract range predicate scalar operator with unknown column statistics will not eval
            if (predicate.isFromPredicateRangeDerive() &&
                    statistics.getColumnStatistics().values().stream().anyMatch(ColumnStatistic::isUnknown)) {
                return false;
            }
            return true;
        }

        private Statistics computeStatisticsAfterPredicate(Statistics statistics, double rowCount) {
            // Do not compute predicate statistics if column statistics is unknown or table row count may inaccurate
            if (statistics.getColumnStatistics().values().stream().anyMatch(ColumnStatistic::isUnknown) ||
                    statistics.isTableRowCountMayInaccurate()) {
                return statistics;
            }
            Statistics.Builder builder = Statistics.buildFrom(statistics);
            // use row count to adjust column statistics distinct values
            double distinctValues = Math.max(1, rowCount);
            statistics.getColumnStatistics().forEach((column, columnStatistic) -> {
                if (columnStatistic.getDistinctValuesCount() > distinctValues) {
                    builder.addColumnStatistic(column,
                            ColumnStatistic.buildFrom(columnStatistic).setDistinctValuesCount(distinctValues).build());
                }
            });
            return builder.build();
        }

        @Override
        public Statistics visit(ScalarOperator predicate, Void context) {
            if (!checkNeedEvalEstimate(predicate)) {
                return statistics;
            }
            double outputRowCount =
                    statistics.getOutputRowCount() * StatisticsEstimateCoefficient.PREDICATE_UNKNOWN_FILTER_COEFFICIENT;
            return computeStatisticsAfterPredicate(
                    statistics.buildFrom(statistics).setOutputRowCount(outputRowCount).build(),
                    outputRowCount);
        }

        @Override
        public Statistics visitInPredicate(InPredicateOperator predicate, Void context) {
            if (!checkNeedEvalEstimate(predicate)) {
                return statistics;
            }
            double selectivity;

            ScalarOperator firstChild = getChildForCastOperator(predicate.getChild(0));
            List<ScalarOperator> otherChildrenList =
                    predicate.getChildren().stream().skip(1).map(this::getChildForCastOperator)
                            .collect(Collectors.toList());
            // 1. compute the inPredicate children column statistics
            ColumnStatistic inColumnStatistic = getExpressionStatistic(firstChild);
            List<ColumnStatistic> otherChildrenColumnStatisticList =
                    otherChildrenList.stream().map(this::getExpressionStatistic).collect(Collectors.toList());

            double columnMaxVal = inColumnStatistic.getMaxValue();
            double columnMinVal = inColumnStatistic.getMinValue();
            double columnDistinctValues = inColumnStatistic.getDistinctValuesCount();

            double otherChildrenMaxValue =
                    otherChildrenColumnStatisticList.stream().mapToDouble(ColumnStatistic::getMaxValue).max()
                            .orElse(Double.POSITIVE_INFINITY);
            double otherChildrenMinValue =
                    otherChildrenColumnStatisticList.stream().mapToDouble(ColumnStatistic::getMinValue).min()
                            .orElse(Double.NEGATIVE_INFINITY);
            double otherChildrenDistinctValues =
                    otherChildrenColumnStatisticList.stream().mapToDouble(ColumnStatistic::getDistinctValuesCount)
                            .sum();
            boolean hasOverlap =
                    Math.max(columnMinVal, otherChildrenMinValue) <= Math.min(columnMaxVal, otherChildrenMaxValue);

            // 2 .compute the in predicate selectivity
            if (inColumnStatistic.isUnknown() || inColumnStatistic.hasNaNValue() ||
                    otherChildrenColumnStatisticList.stream().anyMatch(
                            columnStatistic -> columnStatistic.hasNaNValue() || columnStatistic.isUnknown()) ||
                    !(firstChild.isColumnRef())) {
                // use default selectivity if column statistic is unknown or has NaN values.
                // can not get accurate column statistics if it is not ColumnRef operator
                selectivity = predicate.isNotIn() ?
                        1 - StatisticsEstimateCoefficient.IN_PREDICATE_DEFAULT_FILTER_COEFFICIENT :
                        StatisticsEstimateCoefficient.IN_PREDICATE_DEFAULT_FILTER_COEFFICIENT;
            } else {
                // children column statistics are not unknown.
                selectivity = hasOverlap ?
                        Math.min(1.0, otherChildrenDistinctValues / inColumnStatistic.getDistinctValuesCount()) : 0.0;
                selectivity = predicate.isNotIn() ? 1 - selectivity : selectivity;
            }
            // avoid not in predicate too small
            if (predicate.isNotIn() && Precision.equals(selectivity, 0.0, 0.000001d)) {
                selectivity = 1 - StatisticsEstimateCoefficient.IN_PREDICATE_DEFAULT_FILTER_COEFFICIENT;
            }

            double rowCount = Math.min(statistics.getOutputRowCount() * selectivity, statistics.getOutputRowCount());

            // 3. compute the inPredicate first child column statistics after in predicate
            if (otherChildrenColumnStatisticList.stream()
                    .noneMatch(columnStatistic -> columnStatistic.hasNaNValue() || columnStatistic.isUnknown()) &&
                    !predicate.isNotIn() && hasOverlap) {
                columnMaxVal = Math.min(columnMaxVal, otherChildrenMaxValue);
                columnMinVal = Math.max(columnMinVal, otherChildrenMinValue);
                columnDistinctValues = Math.min(columnDistinctValues, otherChildrenDistinctValues);
            }
            ColumnStatistic newInColumnStatistic =
                    ColumnStatistic.buildFrom(inColumnStatistic).setDistinctValuesCount(columnDistinctValues)
                            .setMinValue(columnMinVal)
                            .setMaxValue(columnMaxVal).build();

            // only columnRefOperator could add column statistic to statistics
            Optional<ColumnRefOperator> childOpt =
                    firstChild.isColumnRef() ? Optional.of((ColumnRefOperator) firstChild) : Optional.empty();

            Statistics inStatistics = childOpt.map(operator ->
                    Statistics.buildFrom(statistics).setOutputRowCount(rowCount).
                            addColumnStatistic(operator, newInColumnStatistic).build()).
                    orElseGet(() -> Statistics.buildFrom(statistics).setOutputRowCount(rowCount).build());
            return computeStatisticsAfterPredicate(inStatistics, rowCount);
        }

        @Override
        public Statistics visitIsNullPredicate(IsNullPredicateOperator predicate, Void context) {
            if (!checkNeedEvalEstimate(predicate)) {
                return statistics;
            }
            double selectivity = 1;
            List<ColumnRefOperator> children = Utils.extractColumnRef(predicate);
            if (children.size() != 1) {
                selectivity = predicate.isNotNull() ?
                        1 - StatisticsEstimateCoefficient.IS_NULL_PREDICATE_DEFAULT_FILTER_COEFFICIENT :
                        StatisticsEstimateCoefficient.IS_NULL_PREDICATE_DEFAULT_FILTER_COEFFICIENT;
                double rowCount = statistics.getOutputRowCount() * selectivity;
                return Statistics.buildFrom(statistics).setOutputRowCount(rowCount).build();
            }
            ColumnStatistic isNullColumnStatistic = statistics.getColumnStatistic(children.get(0));
            if (isNullColumnStatistic.isUnknown()) {
                selectivity = predicate.isNotNull() ?
                        1 - StatisticsEstimateCoefficient.IS_NULL_PREDICATE_DEFAULT_FILTER_COEFFICIENT :
                        StatisticsEstimateCoefficient.IS_NULL_PREDICATE_DEFAULT_FILTER_COEFFICIENT;
            } else {
                selectivity = predicate.isNotNull() ? 1 - isNullColumnStatistic.getNullsFraction() :
                        isNullColumnStatistic.getNullsFraction();
            }
            // avoid estimate selectivity too small because of the error of null fraction
            selectivity =
                    Math.max(selectivity, StatisticsEstimateCoefficient.IS_NULL_PREDICATE_DEFAULT_FILTER_COEFFICIENT);
            double rowCount = statistics.getOutputRowCount() * selectivity;
            return computeStatisticsAfterPredicate(Statistics.buildFrom(statistics).setOutputRowCount(rowCount).
                    addColumnStatistics(
                            ImmutableMap.of(children.get(0), ColumnStatistic.buildFrom(isNullColumnStatistic).
                                    setNullsFraction(predicate.isNotNull() ? 0.0 : 1.0).build())).build(), rowCount);

        }

        @Override
        public Statistics visitBinaryPredicate(BinaryPredicateOperator predicate, Void context) {
            if (!checkNeedEvalEstimate(predicate)) {
                return statistics;
            }
            Preconditions.checkState(predicate.getChildren().size() == 2);
            ScalarOperator leftChild = predicate.getChild(0);
            ScalarOperator rightChild = predicate.getChild(1);
            Preconditions.checkState(!(leftChild.isConstantRef() && rightChild.isConstantRef()),
                    "ConstantRef-cmp-ConstantRef not supported here, should be eliminated earlier: " +
                            predicate.toString());
            Preconditions.checkState(!(leftChild.isConstant() && rightChild.isVariable()),
                    "Constant-cmp-Column not supported here, should be deal earlier: " + predicate.toString());
            // For CastOperator, we need use child as column statistics
            leftChild = getChildForCastOperator(leftChild);
            rightChild = getChildForCastOperator(rightChild);
            // compute left and right column statistics
            ColumnStatistic leftColumnStatistic = getExpressionStatistic(leftChild);
            ColumnStatistic rightColumnStatistic = getExpressionStatistic(rightChild);
            // do not use NaN to estimate predicate
            if (leftColumnStatistic.hasNaNValue()) {
                leftColumnStatistic =
                        ColumnStatistic.buildFrom(leftColumnStatistic).setMaxValue(Double.POSITIVE_INFINITY)
                                .setMinValue(Double.NEGATIVE_INFINITY).build();
            }
            if (rightColumnStatistic.hasNaNValue()) {
                rightColumnStatistic =
                        rightColumnStatistic.buildFrom(rightColumnStatistic).setMaxValue(Double.POSITIVE_INFINITY)
                                .setMinValue(Double.NEGATIVE_INFINITY).build();
            }

            if (leftChild.isVariable()) {
                Optional<ColumnRefOperator> leftChildOpt;
                // only columnRefOperator could add column statistic to statistics
                leftChildOpt = leftChild.isColumnRef() ? Optional.of((ColumnRefOperator) leftChild) : Optional.empty();

                if (rightChild.isConstant()) {
                    OptionalDouble constant =
                            (rightColumnStatistic.isInfiniteRange()) ?
                                    OptionalDouble.empty() : OptionalDouble.of(rightColumnStatistic.getMaxValue());
                    Statistics binaryStats =
                            BinaryPredicateStatisticCalculator.estimateColumnToConstantComparison(leftChildOpt,
                                    leftColumnStatistic, predicate, constant, statistics);
                    return computeStatisticsAfterPredicate(binaryStats, binaryStats.getOutputRowCount());
                } else {
                    Statistics binaryStats = BinaryPredicateStatisticCalculator.estimateColumnToColumnComparison(
                            leftChild, leftColumnStatistic,
                            rightChild, rightColumnStatistic,
                            predicate, statistics);
                    return computeStatisticsAfterPredicate(binaryStats, binaryStats.getOutputRowCount());
                }
            } else {
                // constant compare constant
                double outputRowCount = statistics.getOutputRowCount() *
                        StatisticsEstimateCoefficient.CONSTANT_TO_CONSTANT_PREDICATE_COEFFICIENT;
                return computeStatisticsAfterPredicate(
                        Statistics.buildFrom(statistics).setOutputRowCount(outputRowCount).build(), outputRowCount);
            }
        }

        @Override
        public Statistics visitCompoundPredicate(CompoundPredicateOperator predicate, Void context) {
            if (!checkNeedEvalEstimate(predicate)) {
                return statistics;
            }

            if (predicate.isAnd()) {
                Preconditions.checkState(predicate.getChildren().size() == 2);
                Statistics leftStatistics = predicate.getChild(0).accept(this, null);
                Statistics andStatistics = predicate.getChild(1)
                        .accept(new PredicateStatisticsCalculator.PredicateStatisticsCalculatingVisitor(leftStatistics),
                                null);
                return computeStatisticsAfterPredicate(andStatistics, andStatistics.getOutputRowCount());
            } else if (predicate.isOr()) {
                Preconditions.checkState(predicate.getChildren().size() == 2);
                Statistics leftStatistics = predicate.getChild(0).accept(this, null);
                Statistics rightStatistics = predicate.getChild(1).accept(this, null);
                Statistics andStatistics = predicate.getChild(1)
                        .accept(new PredicateStatisticsCalculator.PredicateStatisticsCalculatingVisitor(leftStatistics),
                                null);
                double rowCount = leftStatistics.getOutputRowCount() + rightStatistics.getOutputRowCount() -
                        andStatistics.getOutputRowCount();
                rowCount = Math.min(rowCount, statistics.getOutputRowCount());
                return computeStatisticsAfterPredicate(
                        computeOrPredicateStatistics(leftStatistics, rightStatistics, statistics, rowCount), rowCount);
            } else {
                Preconditions.checkState(predicate.getChildren().size() == 1);
                Statistics inputStatistics = predicate.getChild(0).accept(this, null);
                double rowCount = Math.max(0, statistics.getOutputRowCount() - inputStatistics.getOutputRowCount());
                return computeStatisticsAfterPredicate(
                        Statistics.buildFrom(statistics).setOutputRowCount(rowCount).build(), rowCount);
            }
        }

        public Statistics computeOrPredicateStatistics(Statistics leftStatistics, Statistics rightStatistics,
                                                       Statistics inputStatistics, double rowCount) {
            Statistics.Builder builder = Statistics.buildFrom(inputStatistics);
            builder.setOutputRowCount(rowCount);
            leftStatistics.getColumnStatistics().forEach((columnRefOperator, columnStatistic) -> {
                ColumnStatistic.Builder columnBuilder = ColumnStatistic.buildFrom(columnStatistic);
                ColumnStatistic rightColumnStatistic = rightStatistics.getColumnStatistic(columnRefOperator);
                columnBuilder.setMinValue(Math.min(columnStatistic.getMinValue(), rightColumnStatistic.getMinValue()));
                columnBuilder.setMaxValue(Math.max(columnStatistic.getMaxValue(), rightColumnStatistic.getMaxValue()));
                builder.addColumnStatistic(columnRefOperator, columnBuilder.build());
            });
            return builder.build();
        }

        @Override
        public Statistics visitConstant(ConstantOperator constant, Void context) {
            if (constant.getBoolean()) {
                return statistics;
            } else {
                return Statistics.buildFrom(statistics).setOutputRowCount(0.0).build();
            }
        }

        private ScalarOperator getChildForCastOperator(ScalarOperator operator) {
            if (operator instanceof CastOperator) {
                Preconditions.checkState(operator.getChildren().size() == 1);
                operator = getChildForCastOperator(operator.getChild(0));
            }
            return operator;
        }

        private ColumnStatistic getExpressionStatistic(ScalarOperator operator) {
            return ExpressionStatisticCalculator.calculate(operator, statistics);
        }
    }
}
