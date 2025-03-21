// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.plan;

import com.starrocks.common.FeConstants;
import com.starrocks.qe.SessionVariable;
import org.junit.Assert;
import org.junit.Test;

public class LimitTest extends PlanTestBase {

    @Test
    public void testLimit() throws Exception {
        String sql = "select v1 from t0 limit 1";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PLAN FRAGMENT 0\n"
                + " OUTPUT EXPRS:1: v1\n"
                + "  PARTITION: RANDOM\n"
                + "\n"
                + "  RESULT SINK\n"
                + "\n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t0\n"
                + "     PREAGGREGATION: ON\n"
                + "     partitions=0/1\n"
                + "     rollup: t0\n"
                + "     tabletRatio=0/0\n"
                + "     tabletList=\n"
                + "     cardinality=1\n"
                + "     avgRowSize=1.0\n"
                + "     numNodes=0\n"
                + "     limit: 1"));
    }

    @Test
    public void testLimitWithHaving() throws Exception {
        String sql = "SELECT v1, sum(v3) as v from t0 where v2 = 0 group by v1 having sum(v3) > 0 limit 10";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("having: 4: sum > 0"));
        Assert.assertTrue(planFragment.contains("limit: 10"));
    }

    @Test
    public void testWindowLimitPushdown() throws Exception {
        String sql = "select lag(v1, 1,1) OVER () from t0 limit 1";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  |  window: ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING\n" +
                "  |  limit: 1"));
    }

    @Test
    public void testSelectStarWhereSubQueryLimit1() throws Exception {
        String sql = "SELECT * FROM t0 where v1 = (select v1 from t0 limit 1);";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("ASSERT NUMBER OF ROWS"));
    }

    @Test
    public void testCrossJoinWithLimit() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select * from t0 join t1 on t0.v2 = t1.v4 limit 2";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("3:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 4: v4 = 2: v2\n" +
                "  |  limit: 2"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testLimit0WithAgg() throws Exception {
        String queryStr = "select count(*) from t0 limit 0";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("OUTPUT EXPRS:4: count"));
        Assert.assertTrue(explainString.contains("0:EMPTYSET"));
    }

    @Test
    public void testSubQueryWithLimit0() throws Exception {
        String queryStr = "select v1 from (select * from t0 limit 0) t";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("0:EMPTYSET"));
    }

    @Test
    public void testAggSubQueryWithLimit0() throws Exception {
        String queryStr = "select sum(a) from (select v1 as a from t0 limit 0) t";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("0:EMPTYSET"));
    }

    @Test
    public void testExceptLimit() throws Exception {
        String queryStr = "select 1 from (select 1, 3 from t0 except select 2, 3 ) as a limit 3";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  6:Project\n"
                + "  |  <slot 10> : 1\n"
                + "  |  limit: 3\n"
                + "  |  \n"
                + "  0:EXCEPT\n"
                + "  |  limit: 3\n"));

        Assert.assertTrue(explainString.contains("  2:Project\n"
                + "  |  <slot 4> : 1\n"
                + "  |  <slot 5> : 3\n"
                + "  |  \n"
                + "  1:OlapScanNode"));
    }

    @Test
    public void testIntersectLimit() throws Exception {
        String queryStr = "select 1 from (select 1, 3 from t0 intersect select 2, 3 ) as a limit 3";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  6:Project\n"
                + "  |  <slot 10> : 1\n"
                + "  |  limit: 3\n"
                + "  |  \n"
                + "  0:INTERSECT\n"
                + "  |  limit: 3\n"));

        Assert.assertTrue(explainString.contains("  2:Project\n"
                + "  |  <slot 4> : 1\n"
                + "  |  <slot 5> : 3\n"
                + "  |  \n"
                + "  1:OlapScanNode"));
    }

    @Test
    public void testCountStarWithLimitForOneAggStage() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        String sql = "select count(*) from (select v1 from t0 order by v2 limit 10,20) t;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("3:AGGREGATE (update finalize)"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testSortWithLimitSubQuery() throws Exception {
        String sql = "select * from (select v1, v2 from t0 limit 10) a order by a.v1";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select * from (select v1, v2 from t0 limit 10) a order by a.v1 limit 1000";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select * from (select v1, v2 from t0 limit 10) a order by a.v1 limit 1";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select * from (select v1, v2 from t0 limit 1) a order by a.v1 limit 10,1";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 1"));
    }

    @Test
    public void testAggWithLimitSubQuery() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select a.v1 from (select v1, v2 from t0 limit 10) a group by a.v1";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select a.v2 from (select v1, v2 from t0 limit 10) a group by a.v2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select count(a.v2) from (select v1, v2 from t0 limit 10) a";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select count(a.v2) from (select v1, v2 from t0 limit 10) a group by a.v2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testWindowWithLimitSubQuery() throws Exception {
        String sql = "select sum(a.v1) over(partition by a.v2) from (select v1, v2 from t0 limit 10) a";
        String plan = getFragmentPlan(sql);

        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select sum(a.v1) over(partition by a.v2 order by a.v1) from (select v1, v2 from t0 limit 10) a";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n" +
                "     limit: 10"));

        sql = "select sum(a.v1) over() from (select v1, v2 from t0 limit 10) a";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:EXCHANGE\n"
                + "     limit: 10\n"));
    }

    @Test
    public void testJoinWithLimitSubQuery() throws Exception {
        String sql = "select * from (select v1, v2 from t0 limit 10) a join " +
                "(select v1, v2 from t0 limit 1) b";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("    EXCHANGE ID: 03\n" +
                "    UNPARTITIONED"));
        Assert.assertTrue(plan.contains("    EXCHANGE ID: 01\n"
                + "    UNPARTITIONED\n"));
    }

    @Test
    public void testJoinWithLimitSubQuery1() throws Exception {
        String sql = "select * from (select v1, v2 from t0 limit 10) a join [broadcast] " +
                "(select v1, v2 from t0 limit 1) b on a.v1 = b.v1";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("    EXCHANGE ID: 03\n" +
                "    UNPARTITIONED"));

    }

    @Test
    public void testJoinWithLimitSubQuery2() throws Exception {
        String sql = "select * from (select v1, v2 from t0) a join [broadcast] " +
                "(select v1, v2 from t0 limit 1) b on a.v1 = b.v1";
        String plan = getFragmentPlan(sql);

        Assert.assertTrue(plan.contains("    EXCHANGE ID: 02\n" +
                "    UNPARTITIONED"));
    }

    @Test
    public void testJoinWithLimitSubQuery3() throws Exception {
        String sql = "select * from (select v1, v2 from t0 limit 10) a join [shuffle] " +
                "(select v1, v2 from t0 limit 1) b on a.v1 = b.v1";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (PARTITIONED)"));
        Assert.assertTrue(plan.contains("  |----5:EXCHANGE\n" +
                "  |       limit: 1"));
        Assert.assertTrue(plan.contains("  2:EXCHANGE\n" +
                "     limit: 10"));
    }

    @Test
    public void testJoinWithLimitSubQuery4() throws Exception {
        String sql = "select * from (select v1, v2 from t0) a join [shuffle] " +
                "(select v4 from t1 limit 1) b on a.v1 = b.v4";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (PARTITIONED)"));
    }

    @Test
    public void testJoinWithLimitSubQuery5() throws Exception {
        String sql = "select * from (select v1, v2 from t0 limit 10) a join [shuffle] " +
                "(select v4 from t1 ) b on a.v1 = b.v4";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (PARTITIONED)"));
    }

    @Test
    public void testUnionWithLimitSubQuery() throws Exception {
        String sql = "select v1, v2 from t0 union all " +
                "select v1, v2 from t0 limit 1 ";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("    EXCHANGE ID: 02\n" +
                "    UNPARTITIONED"));
        Assert.assertTrue(plan.contains("    EXCHANGE ID: 05\n" +
                "    UNPARTITIONED"));

        sql = "select v1, v2 from t0 union all " +
                "select a.v1, a.v2 from (select v1, v2 from t0 limit 1) a ";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("    EXCHANGE ID: 04\n" +
                "    UNPARTITIONED"));
    }

    @Test
    public void testJoinLimit() throws Exception {
        String sql;
        String plan;
        sql = "select * from t0 inner join t1 on t0.v1 = t1.v4 limit 10";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (BROADCAST)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: \n"
                + "  |  equal join conjunct: 1: v1 = 4: v4\n"
                + "  |  limit: 10\n"
                + "  |  \n"
                + "  |----2:EXCHANGE\n"
                + "  |    \n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t0"));

        sql = "select * from t0 left anti join t1 on t0.v1 = t1.v4 limit 10";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: LEFT ANTI JOIN (BROADCAST)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: \n"
                + "  |  equal join conjunct: 1: v1 = 4: v4\n"
                + "  |  limit: 10\n"
                + "  |  \n"
                + "  |----2:EXCHANGE\n"
                + "  |    \n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t0\n"));

        sql = "select * from t0 right semi join t1 on t0.v1 = t1.v4 limit 10";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: LEFT SEMI JOIN (BROADCAST)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: \n"
                + "  |  equal join conjunct: 4: v4 = 1: v1\n"
                + "  |  limit: 10\n"
                + "  |  \n"
                + "  |----2:EXCHANGE\n"
                + "  |    \n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t1\n"));
    }

    @Test
    public void testJoinLimitLeft() throws Exception {
        String sql = "select * from t0 left outer join t1 on t0.v1 = t1.v4 limit 10";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  limit: 10\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode"));
        Assert.assertTrue(plan.contains("     TABLE: t0\n"
                + "     PREAGGREGATION: ON\n"
                + "     partitions=0/1\n"
                + "     rollup: t0\n"
                + "     tabletRatio=0/0\n"
                + "     tabletList=\n"
                + "     cardinality=1\n"
                + "     avgRowSize=3.0\n"
                + "     numNodes=0\n"
                + "     limit: 10"));
    }

    @Test
    public void testJoinLimitFull() throws Exception {
        String sql;
        String plan;
        sql = "select * from t0 full outer join t1 on t0.v1 = t1.v4 limit 10";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  4:HASH JOIN\n"
                + "  |  join op: FULL OUTER JOIN (PARTITIONED)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: \n"
                + "  |  equal join conjunct: 1: v1 = 4: v4\n"
                + "  |  limit: 10\n"
                + "  |  \n"
                + "  |----3:EXCHANGE\n"
                + "  |       limit: 10\n"
                + "  |    \n"
                + "  1:EXCHANGE\n"
                + "     limit: 10\n"));

        sql = "select * from t0, t1 limit 10";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates is NULL.\n" +
                "  |  limit: 10\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |       limit: 10\n" +
                "  |    \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0"));
    }

    @Test
    public void testSqlSelectLimitSession() throws Exception {
        connectContext.getSessionVariable().setSqlSelectLimit(10);
        String sql = "select * from test_all_type";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("limit: 10"));

        connectContext.getSessionVariable().setSqlSelectLimit(10);
        sql = "select * from test_all_type limit 20000";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("limit: 20000"));

        connectContext.getSessionVariable().setSqlSelectLimit(SessionVariable.DEFAULT_SELECT_LIMIT);
        sql = "select * from test_all_type";
        plan = getFragmentPlan(sql);
        Assert.assertFalse(plan.contains("limit: 10"));

        connectContext.getSessionVariable().setSqlSelectLimit(8888);
        sql = "select * from (select * from test_all_type limit 10) as a join " +
                "(select * from test_all_type limit 100) as b";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("limit: 8888"));
        connectContext.getSessionVariable().setSqlSelectLimit(SessionVariable.DEFAULT_SELECT_LIMIT);

        connectContext.getSessionVariable().setSqlSelectLimit(-100);
        sql = "select * from test_all_type";
        plan = getFragmentPlan(sql);
        Assert.assertFalse(plan.contains("limit"));

        connectContext.getSessionVariable().setSqlSelectLimit(0);
        sql = "select * from test_all_type";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("EMPTYSET"));
        connectContext.getSessionVariable().setSqlSelectLimit(SessionVariable.DEFAULT_SELECT_LIMIT);
    }

    @Test
    public void testLimitRightJoin() throws Exception {
        String sql = "select v1 from t0 right outer join t1 on t0.v1 = t1.v4 limit 100";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: RIGHT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  limit: 100"));
        Assert.assertTrue(plan.contains("  |----3:EXCHANGE\n" +
                "  |       limit: 100"));

        sql = "select v1 from t0 full outer join t1 on t0.v1 = t1.v4 limit 100";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: FULL OUTER JOIN (PARTITIONED)"));
    }

    @Test
    public void testLimitLeftJoin() throws Exception {
        String sql = "select v1 from (select * from t0 limit 1) x0 left outer join[shuffle] t1 on x0.v1 = t1.v4";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" 5:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  \n" +
                "  |----4:EXCHANGE\n" +
                "  |    \n" +
                "  2:EXCHANGE\n" +
                "     limit: 1"));

        sql = "select v1 from (select * from t0 limit 10) x0 left outer join t1 on x0.v1 = t1.v4 limit 1";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  limit: 1\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode"));
        Assert.assertTrue(plan.contains("  0:OlapScanNode\n" +
                "     TABLE: t0\n" +
                "     PREAGGREGATION: ON\n" +
                "     partitions=0/1\n" +
                "     rollup: t0\n" +
                "     tabletRatio=0/0\n" +
                "     tabletList=\n" +
                "     cardinality=1\n" +
                "     avgRowSize=1.0\n" +
                "     numNodes=0\n" +
                "     limit: 1"));

        sql = "select v1 from (select * from t0 limit 10) x0 left outer join[shuffle] t1 on x0.v1 = t1.v4 limit 100";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("5:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  limit: 100\n" +
                "  |  \n" +
                "  |----4:EXCHANGE\n" +
                "  |    \n" +
                "  2:EXCHANGE\n" +
                "     limit: 10"));
        Assert.assertTrue(plan.contains("PLAN FRAGMENT 2\n" +
                " OUTPUT EXPRS:\n" +
                "  PARTITION: UNPARTITIONED\n" +
                "\n" +
                "  STREAM DATA SINK\n" +
                "    EXCHANGE ID: 02\n" +
                "    HASH_PARTITIONED: 1: v1\n" +
                "\n" +
                "  1:EXCHANGE\n" +
                "     limit: 10"));

        sql =
                "select v1 from (select * from t0 limit 10) x0 left outer join (select * from t1 limit 5) x1 on x0.v1 = x1.v4 limit 7";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("5:HASH JOIN\n" +
                "  |  join op: RIGHT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 4: v4 = 1: v1\n" +
                "  |  limit: 7\n" +
                "  |  \n" +
                "  |----4:EXCHANGE\n" +
                "  |       limit: 7\n" +
                "  |    \n" +
                "  2:EXCHANGE\n" +
                "     limit: 5"));
        Assert.assertTrue(plan.contains("PLAN FRAGMENT 2\n" +
                " OUTPUT EXPRS:\n" +
                "  PARTITION: UNPARTITIONED\n" +
                "\n" +
                "  STREAM DATA SINK\n" +
                "    EXCHANGE ID: 02\n" +
                "    HASH_PARTITIONED: 4: v4\n" +
                "\n" +
                "  1:EXCHANGE\n" +
                "     limit: 5"));
    }

    @Test
    public void testCrossJoinPushLimit() throws Exception {
        String sql = "select * from t0 cross join t1 on t0.v2 != t1.v5 limit 10";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates: 2: v2 != 5: v5\n" +
                "  |  limit: 10\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode"));

        sql = "select * from t0 inner join t1 on t0.v2 != t1.v5 limit 10";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates: 2: v2 != 5: v5\n" +
                "  |  limit: 10\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode"));
    }

    @Test
    public void testJoinWithLimit() throws Exception {
        String sql = "select t2.v8 from (select v1, v2, v1 as v3 from t0 where v2<> v3 limit 15) as a join t1 " +
                "on a.v3 = t1.v4 join t2 on v4 = v7 join t2 as b" +
                " on a.v1 = b.v7 where b.v8 > t1.v5 limit 10";
        String plan = getFragmentPlan(sql);
        System.out.println(plan);
        // check join on predicate which has expression with limit operator
        sql = "select t2.v8 from (select v1, v2, v1 as v3 from t0 where v2<> v3 limit 15) as a join t1 " +
                "on a.v3 + 1 = t1.v4 join t2 on v4 = v7 join t2 as b" +
                " on a.v3 + 2 = b.v7 where b.v8 > t1.v5 limit 10";
        plan = getFragmentPlan(sql);
        System.out.println(plan);
    }

    @Test
    public void testLimitPushDownJoin() throws Exception {
        String sql = "select * from t0 left join[shuffle] t1 on t0.v2 = t1.v5 where t1.v6 is null limit 2";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: LEFT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 5: v5\n" +
                "  |  other predicates: 6: v6 IS NULL\n" +
                "  |  limit: 2"));
        Assert.assertTrue(plan.contains("     TABLE: t0\n" +
                "     PREAGGREGATION: ON\n" +
                "     partitions=0/1\n" +
                "     rollup: t0\n" +
                "     tabletRatio=0/0\n" +
                "     tabletList=\n" +
                "     cardinality=1\n" +
                "     avgRowSize=3.0\n" +
                "     numNodes=0\n"));
    }

    @Test
    public void testUnionLimit() throws Exception {
        String queryStr = "select 1 from (select 4, 3 from t0 union all select 2, 3 ) as a limit 3";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  2:Project\n"
                + "  |  <slot 4> : 4\n"
                + "  |  limit: 3\n"
                + "  |  \n"
                + "  1:OlapScanNode"));
    }

    @Test
    public void testMergeLimitForFilterNode() throws Exception {
        String sql =
                "SELECT CAST(nullif(subq_0.c1, subq_0.c1) AS INTEGER) AS c0, subq_0.c0 AS c1, 42 AS c2, subq_0.c0 AS "
                        + "c3, subq_0.c1 AS c4\n"
                        +
                        "\t, subq_0.c0 AS c5, subq_0.c0 AS c6\n" +
                        "FROM (\n" +
                        "\tSELECT ref_2.v8 AS c0, ref_2.v8 AS c1\n" +
                        "\tFROM t2 ref_0\n" +
                        "\t\tRIGHT JOIN t1 ref_1 ON ref_0.v7 = ref_1.v4\n" +
                        "\t\tRIGHT JOIN t2 ref_2 ON ref_1.v4 = ref_2.v7\n" +
                        "\tWHERE ref_1.v4 IS NOT NULL\n" +
                        "\tLIMIT 110\n" +
                        ") subq_0\n" +
                        "WHERE CAST(coalesce(true, true) AS BOOLEAN) < true\n" +
                        "LIMIT 157";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("10:SELECT\n" +
                "  |  predicates: coalesce(TRUE, TRUE) < TRUE\n" +
                "  |  limit: 157"));
    }

}
