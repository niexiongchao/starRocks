// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.plan;

import com.starrocks.common.FeConstants;
import com.starrocks.sql.common.StarRocksPlannerException;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Test;

public class AggregateTest extends PlanTestBase {
    @Test
    public void testHaving() throws Exception {
        String sql = "select v2 from t0 group by v2 having v2 > 0";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 2: v2 > 0"));

        sql = "select sum(v1) from t0 group by v2 having v2 > 0";
        planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 2: v2 > 0"));

        sql = "select sum(v1) from t0 group by v2 having sum(v1) > 0";
        planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("having: 4: sum > 0"));
    }

    @Test
    public void testCountDistinctBitmapHll() throws Exception {
        String sql = "select count(distinct v1), count(distinct v2), count(distinct v3), count(distinct v4), " +
                "count(distinct b1), count(distinct b2), count(distinct b3), count(distinct b4) from test_object;";
        getFragmentPlan(sql);

        sql = "select count(distinct v1), count(distinct v2), " +
                "count(distinct h1), count(distinct h2) from test_object";
        getFragmentPlan(sql);
    }

    @Test
    public void testHaving2() throws Exception {
        String sql = "SELECT 8 from t0 group by v1 having avg(v2) < 63;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("having: 4: avg < 63.0"));
    }

    @Test
    public void testGroupByNull() throws Exception {
        String sql = "select count(*) from test_all_type group by null";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("<slot 11> : NULL"));
    }

    @Test
    public void testSumDistinctConst() throws Exception {
        String sql = "select sum(2), sum(distinct 2) from test_all_type";
        String thriftPlan = getThriftPlan(sql);
        Assert.assertTrue(thriftPlan.contains("function_name:multi_distinct_sum"));
    }

    @Test
    public void testGroupByAsAnalyze() throws Exception {
        String sql = "select BITOR(825279661, 1960775729) as a from test_all_type group by a";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("group by: 11: bitor"));
    }

    @Test
    public void testHavingAsAnalyze() throws Exception {
        String sql = "select count(*) as count1 from test_all_type having count1 > 1";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("having: 11: count > 1"));
    }

    @Test
    public void testGroupByAsAnalyze2() throws Exception {
        String sql = "select v1 as v2 from t0 group by v1, v2;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("group by: 1: v1, 2: v2"));
    }

    @Test
    public void testDistinctRedundant() throws Exception {
        String sql = "SELECT DISTINCT + + v1, v1 AS col2 FROM t0;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  |  group by: 1: v1\n"));
    }

    @Test
    public void testColocateAgg() throws Exception {
        FeConstants.runningUnitTest = true;
        String queryStr;
        String explainString;
        queryStr = "select k2, count(k3) from nocolocate3 group by k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  3:AGGREGATE (merge finalize)\n"
                + "  |  output: count(4: count)\n"
                + "  |  group by: 2: k2\n"
                + "  |  \n"
                + "  2:EXCHANGE\n"
                + "\n"
                + "PLAN FRAGMENT 2\n"
                + " OUTPUT EXPRS:"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testDistinctWithGroupBy1() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(3);
        String queryStr = "select avg(v1), count(distinct v1) from t0 group by v1";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains(" 4:AGGREGATE (update finalize)\n" +
                "  |  output: avg(4: avg), count(1: v1)\n" +
                "  |  group by: 1: v1\n" +
                "  |  \n" +
                "  3:AGGREGATE (merge serialize)\n" +
                "  |  output: avg(4: avg)\n" +
                "  |  group by: 1: v1"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testGroupBy2() throws Exception {
        String queryStr = "select avg(v2) from t0 group by v2";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  2:Project\n"
                + "  |  <slot 4> : 4: avg\n"
                + "  |  \n"
                + "  1:AGGREGATE (update finalize)\n"
                + "  |  output: avg(2: v2)\n"
                + "  |  group by: 2: v2\n"
                + "  |  \n"
                + "  0:OlapScanNode"));
    }

    @Test
    public void testAggregateConst() throws Exception {
        String sql = "select 'a', v2, sum(v1) from t0 group by 'a', v2; ";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  2:Project\n"
                + "  |  <slot 2> : 2: v2\n"
                + "  |  <slot 5> : 5: sum\n"
                + "  |  <slot 6> : 'a'\n"
                + "  |  \n"
                + "  1:AGGREGATE (update finalize)\n"
                + "  |  output: sum(1: v1)\n"
                + "  |  group by: 2: v2\n"));
    }

    @Test
    public void testAggregateAllConst() throws Exception {
        String sql = "select 'a', 'b' from t0 group by 'a', 'b'; ";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  3:Project\n"
                + "  |  <slot 4> : 4: expr\n"
                + "  |  <slot 6> : 'b'\n"
                + "  |  \n"
                + "  2:AGGREGATE (update finalize)\n"
                + "  |  group by: 4: expr\n"
                + "  |  \n"
                + "  1:Project\n"
                + "  |  <slot 4> : 'a'\n"
                + "  |  \n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t0"));
    }

    @Test
    public void testAggConstPredicate() throws Exception {
        String queryStr = "select MIN(v1) from t0 having abs(1) = 2";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  1:AGGREGATE (update finalize)\n"
                + "  |  output: min(1: v1)\n"
                + "  |  group by: \n"
                + "  |  having: abs(1) = 2\n"));
    }

    @Test
    public void testSumDistinctSmallInt() throws Exception {
        String sql = " select sum(distinct t1b) from test_all_type;";
        String thriftPlan = getThriftPlan(sql);
        Assert.assertTrue(thriftPlan.contains("arg_types:[TTypeDesc(types:" +
                "[TTypeNode(type:SCALAR, scalar_type:TScalarType(type:SMALLINT))])]"));
    }

    @Test
    public void testCountDistinctWithMultiColumns() throws Exception {
        String sql = "select count(distinct t1b,t1c) from test_all_type";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("6:AGGREGATE (merge finalize)"));
        Assert.assertTrue(plan.contains("4:AGGREGATE (update serialize)\n" +
                "  |  output: count(if(2: t1b IS NULL, NULL, 3: t1c))"));
    }

    @Test
    public void testCountDistinctWithIfNested() throws Exception {
        String sql = "select count(distinct t1b,t1c,t1d) from test_all_type";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("output: count(if(2: t1b IS NULL, NULL, if(3: t1c IS NULL, NULL, 4: t1d)))"));

        sql = "select count(distinct t1b,t1c,t1d,t1e) from test_all_type group by t1f";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(
                "output: count(if(2: t1b IS NULL, NULL, if(3: t1c IS NULL, NULL, if(4: t1d IS NULL, NULL, 5: t1e))))"));
    }

    @Test
    public void testCountDistinctGroupByWithMultiColumns() throws Exception {
        String sql = "select count(distinct t1b,t1c) from test_all_type group by t1d";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("4:AGGREGATE (update finalize)\n" +
                "  |  output: count(if(2: t1b IS NULL, NULL, 3: t1c))"));
    }

    @Test
    public void testCountDistinctWithDiffMultiColumns() throws Exception {
        String sql = "select count(distinct t1b,t1c), count(distinct t1b,t1d) from test_all_type";
        try {
            getFragmentPlan(sql);
        } catch (StarRocksPlannerException e) {
            Assert.assertEquals(
                    "The query contains multi count distinct or sum distinct, each can't have multi columns.",
                    e.getMessage());
        }
    }

    @Test
    public void testCountDistinctWithSameMultiColumns() throws Exception {
        String sql = "select count(distinct t1b,t1c), count(distinct t1b,t1c) from test_all_type";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("6:AGGREGATE (merge finalize)"));

        sql = "select count(distinct t1b,t1c), count(distinct t1b,t1c) from test_all_type group by t1d";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("4:AGGREGATE (update finalize)"));
    }

    @Test
    public void testNullableSameWithChildrenFunctions() throws Exception {
        String sql = "select distinct day(id_datetime) from test_all_type_partition_by_datetime";
        String plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains(" 1:Project\n" +
                "  |  output columns:\n" +
                "  |  11 <-> day[([2: id_datetime, DATETIME, false]); args: DATETIME; result: TINYINT; args nullable: false; result nullable: false]"));

        sql = "select distinct 2 * v1 from t0_not_null";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("2:AGGREGATE (update finalize)\n" +
                "  |  group by: [4: expr, BIGINT, false]"));

        sql = "select distinct cast(2.0 as decimal) * v1 from t0_not_null";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("2:AGGREGATE (update finalize)\n" +
                "  |  group by: [4: expr, DECIMAL64(18,0), true]"));
    }

    @Test
    public void testCountDistinctMultiColumns2() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        String sql = "select count(distinct L_SHIPMODE,L_ORDERKEY) from lineitem";
        String plan = getFragmentPlan(sql);
        // check use 4 stage agg plan
        Assert.assertTrue(plan.contains("6:AGGREGATE (merge finalize)\n" +
                "  |  output: count(18: count)"));
        Assert.assertTrue(plan.contains("4:AGGREGATE (update serialize)\n" +
                "  |  output: count(if(15: L_SHIPMODE IS NULL, NULL, 1: L_ORDERKEY))\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  3:AGGREGATE (merge serialize)\n" +
                "  |  group by: 1: L_ORDERKEY, 15: L_SHIPMODE"));
        Assert.assertTrue(plan.contains("1:AGGREGATE (update serialize)\n" +
                "  |  STREAMING\n" +
                "  |  group by: 1: L_ORDERKEY, 15: L_SHIPMODE"));

        sql = "select count(distinct L_SHIPMODE,L_ORDERKEY) from lineitem group by L_PARTKEY";
        plan = getFragmentPlan(sql);
        // check use 3 stage agg plan
        Assert.assertTrue(plan.contains(" 4:AGGREGATE (update finalize)\n" +
                "  |  output: count(if(15: L_SHIPMODE IS NULL, NULL, 1: L_ORDERKEY))\n" +
                "  |  group by: 2: L_PARTKEY\n" +
                "  |  \n" +
                "  3:AGGREGATE (merge serialize)\n" +
                "  |  group by: 1: L_ORDERKEY, 2: L_PARTKEY, 15: L_SHIPMODE"));
        Assert.assertTrue(plan.contains("1:AGGREGATE (update serialize)\n" +
                "  |  STREAMING\n" +
                "  |  group by: 1: L_ORDERKEY, 2: L_PARTKEY, 15: L_SHIPMODE"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testCountDistinctBoolTwoPhase() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        String sql = "select count(distinct id_bool) from test_bool";
        String plan = getCostExplain(sql);
        Assert.assertTrue(plan.contains("aggregate: multi_distinct_count[([11: id_bool, BOOLEAN, true]); " +
                "args: BOOLEAN; result: VARCHAR;"));

        sql = "select sum(distinct id_bool) from test_bool";
        plan = getCostExplain(sql);
        Assert.assertTrue(plan.contains("aggregate: multi_distinct_sum[([11: id_bool, BOOLEAN, true]); " +
                "args: BOOLEAN; result: VARCHAR;"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testCountDistinctFloatTwoPhase() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        String sql = "select count(distinct t1e) from test_all_type";
        String plan = getCostExplain(sql);
        Assert.assertTrue(plan.contains("aggregate: multi_distinct_count[([5: t1e, FLOAT, true]); " +
                "args: FLOAT; result: VARCHAR; args nullable: true; result nullable: false"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testCountDistinctGroupByFunction() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select L_LINENUMBER, date_trunc(\"day\",L_SHIPDATE) as day ,count(distinct L_ORDERKEY) from " +
                "lineitem group by L_LINENUMBER, day";
        String plan = getFragmentPlan(sql);
        // check use two stage aggregate
        Assert.assertTrue(plan.contains("2:AGGREGATE (update serialize)\n" +
                "  |  STREAMING\n" +
                "  |  output: multi_distinct_count(1: L_ORDERKEY)"));
        Assert.assertTrue(plan.contains("4:AGGREGATE (merge finalize)\n" +
                "  |  output: multi_distinct_count(19: count)"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testReplicatedAgg() throws Exception {
        connectContext.getSessionVariable().setEnableReplicationJoin(true);

        String sql = "select value, SUM(id) from join1 group by value";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: sum(2: id)\n" +
                "  |  group by: 3: value\n" +
                "  |  \n" +
                "  0:OlapScanNode"));

        connectContext.getSessionVariable().setEnableReplicationJoin(false);
    }

    @Test
    public void testDuplicateAggregateFn() throws Exception {
        String sql = "select bitmap_union_count(b1) from test_object having count(distinct b1) > 2;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains(" OUTPUT EXPRS:13: bitmap_union_count\n" +
                "  PARTITION: RANDOM\n" +
                "\n" +
                "  RESULT SINK\n" +
                "\n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  output: bitmap_union_count(5: b1)\n" +
                "  |  group by: \n" +
                "  |  having: 13: bitmap_union_count > 2"));
    }

    @Test
    public void testDuplicateAggregateFn2() throws Exception {
        String sql = "select bitmap_union_count(b1), count(distinct b1) from test_object;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  2:Project\n" +
                "  |  <slot 13> : 13: bitmap_union_count\n" +
                "  |  <slot 14> : 13: bitmap_union_count\n" +
                "  |  \n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  output: bitmap_union_count(5: b1)"));
    }

    @Test
    public void testMergeAggregateNormal() throws Exception {
        String sql;
        String plan;

        sql = "select distinct x1 from (select distinct v1 as x1 from t0) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  RESULT SINK\n" +
                "\n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  group by: 1: v1\n"));

        sql = "select sum(x1) from (select sum(v1) as x1 from t0) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  RESULT SINK\n" +
                "\n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  output: sum(1: v1)\n" +
                "  |  group by: \n"));

        sql = "select SUM(x1) from (select v2, sum(v1) as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  RESULT SINK\n" +
                "\n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  output: sum(1: v1)\n" +
                "  |  group by: \n"));

        sql = "select v2, SUM(x1) from (select v2, v3, sum(v1) as x1 from t0 group by v2, v3) as q group by v2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: sum(1: v1)\n" +
                "  |  group by: 2: v2\n" +
                "  |  \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0"));

        sql = "select SUM(x1) from (select v2, sum(distinct v1), sum(v3) as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: sum(3: v3)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0"));

        sql = "select MAX(x1) from (select v2 as x1 from t0 union select v3 from t0) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  7:AGGREGATE (merge finalize)\n" +
                "  |  output: max(8: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  6:EXCHANGE\n" +
                "\n" +
                "PLAN FRAGMENT 1\n" +
                " OUTPUT EXPRS:\n" +
                "  PARTITION: RANDOM\n" +
                "\n" +
                "  STREAM DATA SINK\n" +
                "    EXCHANGE ID: 06\n" +
                "    UNPARTITIONED\n" +
                "\n" +
                "  5:AGGREGATE (update serialize)\n" +
                "  |  output: max(7: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  0:UNION"));

        sql = "select MIN(x1) from (select distinct v2 as x1 from t0) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: min(2: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0"));

        sql = "select MIN(x1) from (select v2 as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: min(2: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0"));
    }

    @Test
    public void testMergeAggregateFailed() throws Exception {
        String sql;
        String plan;
        sql = "select avg(x1) from (select avg(v1) as x1 from t0) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: avg(1: v1)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  0:OlapScanNode"));

        sql = "select SUM(v2) from (select v2, sum(v1) as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  2:AGGREGATE (update finalize)\n" +
                "  |  output: sum(2: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  group by: 2: v2\n"));
        sql = "select SUM(v2) from (select v2, sum(distinct v2) as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  2:AGGREGATE (update finalize)\n" +
                "  |  output: sum(2: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  group by: 2: v2\n"));
        sql = "select sum(distinct x1) from (select v2, sum(v2) as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: sum(2: v2)\n" +
                "  |  group by: 2: v2\n" +
                "  |  \n" +
                "  0:OlapScanNode\n"));

        sql = "select SUM(x1) from (select v2 as x1 from t0 union select v3 from t0) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  7:AGGREGATE (merge finalize)\n" +
                "  |  group by: 7: v2\n" +
                "  |  \n" +
                "  6:EXCHANGE\n" +
                "\n" +
                "PLAN FRAGMENT 2\n" +
                " OUTPUT EXPRS:\n" +
                "  PARTITION: RANDOM\n" +
                "\n" +
                "  STREAM DATA SINK\n" +
                "    EXCHANGE ID: 06\n" +
                "    HASH_PARTITIONED: 7: v2\n" +
                "\n" +
                "  5:AGGREGATE (update serialize)\n" +
                "  |  STREAMING\n" +
                "  |  group by: 7: v2\n"));

        sql = "select SUM(x1) from (select v2 as x1 from t0 group by v2) as q";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  2:AGGREGATE (update finalize)\n" +
                "  |  output: sum(2: v2)\n" +
                "  |  group by: \n" +
                "  |  \n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  group by: 2: v2\n" +
                "  |  \n" +
                "  0:OlapScanNode"));
    }

    @Test
    public void testMultiCountDistinctAggPhase() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select count(distinct t1a,t1b), avg(t1c) from test_all_type";
        String plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains(" 2:AGGREGATE (update serialize)\n" +
                "  |  aggregate: count[(if[(1: t1a IS NULL, NULL, [2: t1b, SMALLINT, true]); args: BOOLEAN,SMALLINT,SMALLINT; result: SMALLINT; args nullable: true; result nullable: true]); args: SMALLINT; result: BIGINT; args nullable: true; result nullable: false], avg[([12: avg, VARCHAR, true]); args: INT; result: VARCHAR; args nullable: true; result nullable: true]"));
        Assert.assertTrue(plan.contains(" 1:AGGREGATE (update serialize)\n" +
                "  |  aggregate: avg[([3: t1c, INT, true]); args: INT; result: VARCHAR; args nullable: true; result nullable: true]\n" +
                "  |  group by: [1: t1a, VARCHAR, true], [2: t1b, SMALLINT, true]"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testMultiCountDistinctType() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select count(distinct t1a,t1b) from test_all_type";
        String plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("2:AGGREGATE (update serialize)\n" +
                "  |  aggregate: count[(if[(1: t1a IS NULL, NULL, [2: t1b, SMALLINT, true]); args: BOOLEAN,SMALLINT,SMALLINT; result: SMALLINT; args nullable: true; result nullable: true]); args: SMALLINT; result: BIGINT; args nullable: true; result nullable: false]"));
        Assert.assertTrue(plan.contains("4:AGGREGATE (merge finalize)\n" +
                "  |  aggregate: count[([11: count, BIGINT, false]); args: SMALLINT; result: BIGINT; args nullable: true; result nullable: false]"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testMultiCountDistinct() throws Exception {
        String queryStr = "select count(distinct k1, k2) from baseall group by k3";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("group by: 1: k1, 2: k2, 3: k3"));

        queryStr = "select count(distinct k1) from baseall group by k3";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("12: count"));
        Assert.assertTrue(explainString.contains("multi_distinct_count(1: k1)"));
        Assert.assertTrue(explainString.contains("group by: 3: k3"));

        queryStr = "select count(distinct k1) from baseall";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("multi_distinct_count(1: k1)"));

        queryStr = "select count(distinct k1, k2),  count(distinct k4) from baseall group by k3";
        starRocksAssert.query(queryStr).analysisError(
                "The query contains multi count distinct or sum distinct, each can't have multi columns.");
    }

    @Test
    public void testVarianceStddevAnalyze() throws Exception {
        String sql = "select stddev_pop(1222) from (select 1) t;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:AGGREGATE (update finalize)\n" +
                "  |  output: stddev_pop(1222)\n" +
                "  |  group by: "));
        Assert.assertTrue(plan.contains("  0:UNION\n" +
                "     constant exprs: \n" +
                "         1"));
    }

    @Test
    public void testCountDistinctRewrite() throws Exception {
        String sql = "select count(distinct id) from test.bitmap_table";
        starRocksAssert.query(sql).explainContains("count(1: id)", "multi_distinct_count(1: id)");

        sql = "select count(distinct id2) from test.bitmap_table";
        starRocksAssert.query(sql).explainContains("count(2: id2)", "bitmap_union_count(2: id2)");

        sql = "select sum(id) / count(distinct id2) from test.bitmap_table";
        starRocksAssert.query(sql).explainContains("output: sum(1: id), bitmap_union_count(2: id2)");

        sql = "select count(distinct id2) from test.hll_table";
        starRocksAssert.query(sql).explainContains("hll_union_agg(2: id2)", "3: count");

        sql = "select sum(id) / count(distinct id2) from test.hll_table";
        starRocksAssert.query(sql).explainContains("sum(1: id), hll_union_agg(2: id2)");

        sql = "select count(distinct id2) from test.bitmap_table group by id order by count(distinct id2)";
        starRocksAssert.query(sql).explainContains();

        sql = "select count(distinct id2) from test.bitmap_table having count(distinct id2) > 0";
        starRocksAssert.query(sql)
                .explainContains("bitmap_union_count(2: id2)", "having: 3: count > 0");

        sql = "select count(distinct id2) from test.bitmap_table order by count(distinct id2)";
        starRocksAssert.query(sql).explainContains("3: count", "3:MERGING-EXCHANGE",
                "order by: <slot 3> 3: count ASC",
                "output: bitmap_union_count(2: id2)");
    }

    @Test
    public void testAggregateTwoLevelToOneLevelOptimization() throws Exception {
        String sql = "SELECT c2, count(*) FROM db1.tbl3 WHERE c1<10 GROUP BY c2;";
        String plan = getFragmentPlan(sql);
        Assert.assertEquals(1, StringUtils.countMatches(plan, "AGGREGATE (update finalize)"));

        sql = " SELECT c2, count(*) FROM (SELECT t1.c2 as c2 FROM db1.tbl3 as t1 INNER JOIN [shuffle] db1.tbl4 " +
                "as t2 ON t1.c2=t2.c2 WHERE t1.c1<10) as t3 GROUP BY c2;";
        plan = getFragmentPlan(sql);
        Assert.assertEquals(1, StringUtils.countMatches(plan, "AGGREGATE (merge finalize)"));

        sql = "SELECT c2, count(*) FROM db1.tbl5 GROUP BY c2;";
        plan = getFragmentPlan(sql);
        Assert.assertEquals(1, StringUtils.countMatches(plan, "AGGREGATE (update finalize)"));

        sql = "SELECT c3, count(*) FROM db1.tbl4 GROUP BY c3;";
        plan = getFragmentPlan(sql);
        Assert.assertEquals(1, StringUtils.countMatches(plan, "AGGREGATE (update finalize)"));
    }

    @Test
    public void testDistinctPushDown() throws Exception {
        String sql = "select distinct k1 from (select distinct k1 from test.pushdown_test) t where k1 > 1";
        starRocksAssert.query(sql).explainContains("  RESULT SINK\n" +
                "\n" +
                "  1:AGGREGATE (update finalize)\n" +
                "  |  group by: 1: k1\n" +
                "  |  \n" +
                "  0:OlapScanNode");
    }

    @Test
    public void testDistinctBinaryPredicateNullable() throws Exception {
        String sql = "select distinct L_ORDERKEY < L_PARTKEY from lineitem";
        String plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains(" 2:AGGREGATE (update finalize)\n" +
                "  |  group by: [18: expr, BOOLEAN, false]"));

        sql = "select distinct v1 <=> v2 from t0";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("2:AGGREGATE (update finalize)\n" +
                "  |  group by: [4: expr, BOOLEAN, false]"));
    }

}
