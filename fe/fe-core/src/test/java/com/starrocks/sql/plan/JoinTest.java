// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.plan;

import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.FeConstants;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.rule.RuleSet;
import com.starrocks.sql.optimizer.rule.transformation.JoinAssociativityRule;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Test;

public class JoinTest extends PlanTestBase {
    @Test
    public void testColocateDistributeSatisfyShuffleColumns() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select * from colocate1 left join colocate2 on colocate1.k1=colocate2.k1;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("colocate: false"));
        Assert.assertTrue(plan.contains("join op: LEFT OUTER JOIN (BROADCAST)"));

        sql = "select * from colocate1 left join colocate2 on colocate1.k1=colocate2.k1 and colocate1.k2=colocate2.k2;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("colocate: true"));
        Assert.assertTrue(plan.contains("join op: LEFT OUTER JOIN (COLOCATE)"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testInnerJoinWithPredicate() throws Exception {
        String sql = "SELECT * from t0 join test_all_type on t0.v1 = test_all_type.t1d where t0.v1 = 1;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 1: v1 = 1"));
    }

    @Test
    public void testInnerJoinWithConstPredicate() throws Exception {
        String sql = "SELECT * from t0 join test_all_type on NOT NULL >= NULL";

        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  0:EMPTYSET\n"));
    }

    @Test
    public void testInnerJoinWithCastPredicate() throws Exception {
        String sql = "SELECT t0.v1 from t0 join test_all_type on t0.v1 = test_all_type.t1c";
        getFragmentPlan(sql);
    }

    @Test
    public void testCorssJoinWithPredicate() throws Exception {
        String sql = "SELECT * from t0 join test_all_type where t0.v1 = 2;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 1: v1 = 2"));
    }

    @Test
    public void testLeftOuterJoinWithPredicate() throws Exception {
        String sql = "SELECT * from t0 left join test_all_type on t0.v1 = test_all_type.t1d where t0.v1 > 1;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 1: v1 > 1"));
    }

    @Test
    public void testCrossJoinToInnerJoin() throws Exception {
        String sql = "SELECT t0.v1 from t0, test_all_type where t0.v1 = test_all_type.t1d";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("join op: INNER JOIN"));
        Assert.assertTrue(planFragment.contains("equal join conjunct: 1: v1 = 7: t1d"));
    }

    @Test
    public void testJoinColumnsPrune() throws Exception {
        String sql = " select count(a.v3) from t0 a join t0 b on a.v3 = b.v3;";
        getFragmentPlan(sql);

        sql = " select a.v2 from t0 a join t0 b on a.v3 = b.v3;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("4:Project\n"
                + "  |  <slot 2> : 2: v2"));
    }

    @Test
    public void testCrossJoin() throws Exception {
        String sql = "SELECT * from t0 join test_all_type;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates is NULL.\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode"));

        sql = "select * from t0 join test_all_type on NOT 69 IS NOT NULL where true";
        planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates is NULL.\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:EMPTYSET"));
    }

    @Test
    public void testFullOuterJoin() throws Exception {
        String sql = "select * from t0 full outer join t1 on t0.v1 = t1.v4 where abs(1) > 2;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("     TABLE: t1\n"
                + "     PREAGGREGATION: ON\n"
                + "     PREDICATES: abs(1) > 2"));
        Assert.assertTrue(planFragment.contains("     TABLE: t0\n"
                + "     PREAGGREGATION: ON\n"
                + "     PREDICATES: abs(1) > 2"));
    }

    @Test
    public void testFullOuterJoinPredicatePushDown() throws Exception {
        String sql = "select * from t0 full outer join t1 on t0.v1 = t1.v4 " +
                " where (NOT (t0.v2 IS NOT NULL))";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("other predicates: 2: v2 IS NULL"));
    }

    @Test
    public void testRightSemiJoinWithFilter() throws Exception {
        String sql = "select t1.v4 from t0 right semi join t1 on t0.v1 = t1.v4 and t0.v1 > 1 ";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 1: v1 > 1"));
    }

    @Test
    public void testColocateJoin() throws Exception {
        FeConstants.runningUnitTest = true;
        String queryStr = "select * from test.colocate1 t1, test.colocate2 t2 " +
                "where t1.k1 = t2.k1 and t1.k2 = t2.k2 and t1.k3 = t2.k3";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        // t1.k1 = t2.k2 not same order with distribute column
        queryStr = "select * from test.colocate1 t1, test.colocate2 t2 " +
                "where t1.k1 = t2.k2 and t1.k2 = t2.k1 and t1.k3 = t2.k3";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr = "select * from test.colocate1 t1, test.colocate2 t2 where t1.k1 = t2.k1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr = "select * from test.colocate1 t1, test.colocate2 t2 where t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr = "select * from test.colocate1 t1, test.colocate2 t2 " +
                "where t1.k1 = t2.k1 and t1.k2 = t2.k2 + 1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testColocateJoinWithOneAggChild() throws Exception {
        FeConstants.runningUnitTest = true;
        String queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, count(k3) from test.colocate2 group by k1,"
                        + " k2) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, k3, count(k3) from test.colocate2 group by"
                        + " k1, k2, k3) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2 and t1.k3 = t2.k3";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 left join test"
                        + ".colocate1 t1 on  "
                        +
                        "t2.k1 = t1.k1 and t2.k2 = t1.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, k3, count(k3) from test.colocate2 group by"
                        + " k1, k2, k3) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, count(k3) from test.colocate2 group by k2,"
                        + " k1) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, count(k3) from test.colocate2 group by k2,"
                        + " k1) t2 on  "
                        +
                        "t1.k1 = t2.k1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, count(k3) from test.colocate2 group by k2,"
                        + " k1) t2 on  "
                        +
                        "t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr =
                "select * from test.colocate1 t1 left join (select k1, k2, count(k3) from test.colocate2 group by k2,"
                        + " k1) t2 on  "
                        +
                        "t1.k1 = t2.k2 and t1.k2 = t2.k1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testColocateJoinWithTwoAggChild() throws Exception {
        FeConstants.runningUnitTest = true;
        String queryStr =
                "select * from (select k1, k2, count(k3) from test.colocate1 group by k1, k2) t1 left join (select "
                        + "k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2, k3, count(k3) from test.colocate1 group by k1, k2, k3) t1 left join "
                        + "(select k1, k2, k3, count(k3) from test.colocate2 group by k1, k2, k3) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2 ";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2, k3, count(k3) from test.colocate1 group by k1, k2, k3) t1 left join "
                        + "(select k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2 ";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2, k3, count(k3) from test.colocate1 group by k1, k2, k3) t1 left join "
                        + "(select k1, k2, k3, count(k3) from test.colocate2 group by k1, k2, k3) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2 and t1.k3 = t2.k3";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2, count(k3) from test.colocate1 group by k2, k1) t1 left join (select "
                        + "k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 on  "
                        +
                        "t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2, count(k3) from test.colocate1 group by k1, k2) t1 left join (select "
                        + "k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 on  "
                        +
                        "t1.k2 = t2.k1 and t1.k1 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr =
                "select * from (select k1, k2, count(k3) from test.colocate1 group by k1, k2) t1 left join (select "
                        + "k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 on  "
                        +
                        "t1.k1 = t2.k1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testColocateJoinWithTwoAggChild2() throws Exception {
        String queryStr =
                "select * from (select k2, count(k3) from test.colocate1 group by k2) t1 left join (select "
                        + "k1, k2, count(k3) from test.colocate2 group by k1, k2) t2 on  "
                        + "t1.k2 = t2.k2";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));
    }

    @Test
    public void testCrossJoinEliminate() throws Exception {
        String query = "select t1.* from t0, t2, t3, t1 where t1.v4 = t2.v7 " +
                "and t1.v4 = t3.v1 and t3.v1 = t0.v1";
        String explainString = getFragmentPlan(query);
        Assert.assertFalse(explainString.contains("CROSS JOIN"));
    }

    @Test
    public void testSemiJoinPushDown() throws Exception {
        String sql = "SELECT *\n"
                + "FROM (\n"
                + "    SELECT t0.v1, t0.v2, t0.v3\n"
                + "    FROM t0\n"
                + ") subt0\n"
                + "    LEFT SEMI JOIN (\n"
                + "        SELECT t1.v4, t1.v5, t1.v6\n"
                + "        FROM t1\n"
                + "    ) subt1\n"
                + "    ON subt0.v1 = subt1.v4\n"
                + "        AND subt0.v2 != subt0.v2\n"
                + "        AND subt0.v2 = subt1.v5\n"
                + "        AND (subt0.v3 <= subt0.v3 < subt1.v6) = (subt1.v5)\n";

        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  colocate: false, reason: \n"
                + "  |  equal join conjunct: 4: v4 = 1: v1\n"
                + "  |  equal join conjunct: 5: v5 = 2: v2\n"
                + "  |  other join predicates: CAST(CAST(3: v3 <= 3: v3 AS BIGINT) < 6: v6 AS BIGINT) = 5: v5\n"));
    }

    @Test
    public void testInnerJoinPushDown() throws Exception {
        String sql = "SELECT *\n"
                + "FROM (\n"
                + "    SELECT t0.v1, t0.v2, t0.v3\n"
                + "    FROM t0\n"
                + ") subt0\n"
                + "    INNER JOIN (\n"
                + "        SELECT t1.v4, t1.v5, t1.v6\n"
                + "        FROM t1\n"
                + "    ) subt1\n"
                + "    ON subt0.v1 = subt1.v4\n"
                + "        AND subt0.v2 != subt0.v2\n"
                + "        AND subt0.v2 = subt1.v5\n"
                + "        AND (subt0.v3 <= subt0.v3 < subt1.v6) = (subt1.v5)\n";

        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  colocate: false, reason: \n"
                + "  |  equal join conjunct: 4: v4 = 1: v1\n"
                + "  |  equal join conjunct: 5: v5 = 2: v2\n"
                + "  |  other join predicates: CAST(CAST(3: v3 <= 3: v3 AS BIGINT) < 6: v6 AS BIGINT) = 5: v5\n"));
    }

    @Test
    public void testOuterJoinToInnerWithCast() throws Exception {
        String sql = "select * from test_all_type a left join test_all_type b on a.t1c = b.t1c " +
                "where b.id_date = '2021-05-19'";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN"));
    }

    @Test
    public void testUsingJoin() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select * from t0 as x0 join t0 as x1 using(v1);";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  2:HASH JOIN\n"
                + "  |  join op: INNER JOIN (COLOCATE)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: true\n"
                + "  |  equal join conjunct: 1: v1 = 4: v1"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testJoinAssociativityConst() throws Exception {
        String sql = "SELECT x0.*\n" +
                "FROM (\n" +
                "    SELECT 49 AS v0, v1\n" +
                "    FROM t0\n" +
                "    WHERE v1 is not null\n" +
                ") x0\n" +
                "    INNER JOIN test_all_type s0 ON x0.v0 = s0.t1a\n" +
                "    INNER JOIN tall l1 ON x0.v0 = l1.tf\n" +
                "\n" +
                "WHERE l1.tc < s0.t1c";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:Project\n" +
                "  |  <slot 1> : 1: v1\n" +
                "  |  <slot 4> : 49\n" +
                "  |  <slot 26> : CAST(49 AS VARCHAR(1048576))\n" +
                "  |  \n" +
                "  0:OlapScanNode"));
    }

    @Test
    public void testFourTableShuffleBucketShuffle() throws Exception {
        // check top join use shuffle bucket join
        //                   join(shuffle bucket)
        //                   /                  \
        //              join(partitioned)   join(partitioned)
        String sql = "with join1 as (\n" +
                "  select * from t2 join t3 on v7=v1\n" +
                "), \n" +
                "join2 as (\n" +
                "  select * from t0 join t1 on v1=v4\n" +
                ")\n" +
                "SELECT \n" +
                "  * \n" +
                "from \n" +
                "  join1 \n" +
                "  inner join[shuffle] join2 on v4 = v7;";

        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("10:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))"));
        Assert.assertTrue(plan.contains("4:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)"));
        Assert.assertTrue(plan.contains("9:HASH JOIN\n" +
                "  |    |  join op: INNER JOIN (PARTITIONED)"));
    }

    @Test
    public void testSemiReorder() throws Exception {
        String sql = "select 0 from t0,t1 left semi join t2 on v4 = v7";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("PLAN FRAGMENT 0\n" +
                " OUTPUT EXPRS:10: expr\n" +
                "  PARTITION: RANDOM\n" +
                "\n" +
                "  RESULT SINK\n" +
                "\n" +
                "  8:Project\n" +
                "  |  <slot 10> : 0\n" +
                "  |  \n"));
    }

    @Test
    public void testEmptyNodeWithJoin() throws Exception {
        // check no exception
        String sql = "SELECT\n" +
                "        subq_0.c3, ref_2.id_datetime        \n" +
                "FROM (\n" +
                "        SELECT\n" +
                "                ref_0.id_date AS c3\n" +
                "        FROM\n" +
                "                test_all_type AS ref_0 WHERE FALSE) AS subq_0\n" +
                "        INNER JOIN test_all_type AS ref_1 ON (subq_0.c3 = ref_1.id_date)\n" +
                "        INNER JOIN test_all_type AS ref_2 ON (subq_0.c3 = ref_2.id_datetime)\n" +
                "WHERE\n" +
                "        ref_2.t1a >= ref_1.t1a";
        String plan = getFragmentPlan(sql);
    }

    @Test
    public void testJoinReorderWithExpressions() throws Exception {
        String sql = "SELECT t2.*\n" +
                "FROM t2,(\n" +
                "    SELECT *\n" +
                "    FROM t1 \n" +
                "    WHERE false) subt1 \n" +
                "    LEFT OUTER JOIN (\n" +
                "        SELECT *\n" +
                "        FROM t3 \n" +
                "        WHERE CAST(t3.v1 AS BOOLEAN) BETWEEN (t3.v2) AND (t3.v2) ) subt3 \n" +
                "    ON subt1.v4 = subt3.v1 AND subt1.v4 >= subt3.v1 AND subt1.v5 > subt3.v1 AND subt1.v5 = subt3.v1 \n" +
                "WHERE (subt1.v5 BETWEEN subt1.v5 AND CAST(subt1.v5 AS DECIMAL64)) = subt3.v2;";

        RuleSet mockRule = new RuleSet() {
            @Override
            public void addJoinTransformationRules() {
                this.getTransformRules().clear();
                this.getTransformRules().add(JoinAssociativityRule.getInstance());
            }
        };

        new MockUp<OptimizerContext>() {
            @Mock
            public RuleSet getRuleSet() {
                return mockRule;
            }
        };

        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("PLAN FRAGMENT 3\n" +
                " OUTPUT EXPRS:\n" +
                "  PARTITION: UNPARTITIONED\n" +
                "\n" +
                "  STREAM DATA SINK\n" +
                "    EXCHANGE ID: 02\n" +
                "    HASH_PARTITIONED: 4: v4, 5: v5, 10: cast\n" +
                "\n" +
                "  1:EMPTYSET"));
    }

    @Test
    public void testFullOuterJoinOutputRowCount() throws Exception {
        String sql = "SELECT\n" +
                "    (NOT(FALSE))\n" +
                "FROM (\n" +
                "    SELECT t0.v1,t0.v2,t0.v3 \n" +
                "    FROM t0\n" +
                "    WHERE (t0.v1) BETWEEN(CAST(t0.v2 AS DECIMAL64)) AND(t0.v1)) subt0\n" +
                "    FULL OUTER JOIN (\n" +
                "    SELECT t1.v4, t1.v5, t1.v6\n" +
                "    FROM t1\n" +
                "    WHERE TRUE) subt1 ON subt0.v3 = subt1.v6\n" +
                "    AND subt0.v1 > ((1808124905) % (1336789350))\n" +
                "WHERE\n" +
                "    BITMAP_CONTAINS (bitmap_hash (\"dWyMZ\"), ((- 817000778) - (- 809159836)))\n" +
                "GROUP BY\n" +
                "    1.38432132E8, \"1969-12-20 10:26:22\"\n" +
                "HAVING (COUNT(NULL))\n" +
                "IN(- 1210205071)\n";
        String plan = getFragmentPlan(sql);
        // Just make sure we can get the final plan, and not crashed because of stats calculator error.
        System.out.println(sql);
    }

    @Test
    public void testSelectConstantFormJoin() throws Exception {
        String sql = "SELECT \n" +
                "  * \n" +
                "from \n" +
                "  (\n" +
                "    select \n" +
                "      ref_0.t1c as c5, \n" +
                "      37 as c6 \n" +
                "    from \n" +
                "      test_all_type as ref_0 \n" +
                "      inner join test_all_type as ref_1 on (\n" +
                "        ref_0.t1f = ref_1.t1f\n" +
                "      ) \n" +
                "    where \n" +
                "      ref_0.t1c <> ref_0.t1c\n" +
                "  ) as subq_0 \n" +
                "  inner join part as ref_2 on (subq_0.c5 = ref_2.P_PARTKEY) \n" +
                "  inner join supplier as ref_3 on (subq_0.c5 = ref_3.S_SUPPKEY) \n" +
                "where \n" +
                "  (\n" +
                "    (ref_3.S_NAME > ref_2.P_TYPE) \n" +
                "    and (true)\n" +
                "  ) \n" +
                "  and (\n" +
                "    (subq_0.c6 = ref_3.S_NATIONKEY) \n" +
                "    and (true)\n" +
                "  ) \n" +
                "limit \n" +
                "  45;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("6:Project\n" +
                "  |  <slot 3> : 3: t1c\n" +
                "  |  <slot 40> : CAST(37 AS INT)"));
    }

    @Test
    public void testNullSafeEqualJoin() throws Exception {
        String sql = "select * from t0 join t1 on t0.v3 <=> t1.v4";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("equal join conjunct: 3: v3 <=> 4: v4"));

        sql = "select * from t0 left join t1 on t0.v3 <=> t1.v4";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("equal join conjunct: 3: v3 <=> 4: v4"));
    }

    @Test
    public void testColocateHint() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select * from t0 as x0 inner join t0 as x1 on x0.v1 = x1.v1;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (COLOCATE)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: true"));

        sql = "select * from t0 as x0 inner join[shuffle] t0 as x1 on x0.v1 = x1.v1;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (PARTITIONED)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: "));

        sql = "select * from t0 as x0 inner join[colocate] t0 as x1 on x0.v1 = x1.v1;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (COLOCATE)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: true"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testBucketHint() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select * from t0 as x0 inner join t1 as x1 on x0.v1 = x1.v4;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (BUCKET_SHUFFLE)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: "));

        sql = "select * from t0 as x0 inner join[shuffle] t1 as x1 on x0.v1 = x1.v4;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (PARTITIONED)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: "));

        sql = "select * from t0 as x0 inner join[bucket] t1 as x1 on x0.v1 = x1.v4;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: INNER JOIN (BUCKET_SHUFFLE)\n"
                + "  |  hash predicates:\n"
                + "  |  colocate: false, reason: "));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testJoinOnInDatePredicate() throws Exception {
        String sql =
                "select a.id_datetime from test_all_type as a join test_all_type as b where a.id_date in (b.id_date)";
        String plan = getFragmentPlan(sql);
        Assert.assertFalse(plan.contains("CAST(9: id_date AS DATETIME)"));
        Assert.assertTrue(plan.contains("equal join conjunct: 9: id_date = 19: id_date"));
    }

    @Test
    public void testOnlyCrossJoin() throws Exception {
        String sql = "select * from t0 as x0 join t1 as x1 on (1 = 2) is not null;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates is NULL"));
    }

    @Test
    public void testFailedLeftJoin() {
        String sql = "select * from t0 as x0 left outer join t1 as x1 on (1 = 2) is not null";
        Assert.assertThrows("No equal on predicate in LEFT OUTER JOIN is not supported", SemanticException.class,
                () -> getFragmentPlan(sql));
    }

    @Test
    public void testSemiJoinReorder() throws Exception {
        String sql = "SELECT \n" +
                "  v2 \n" +
                "FROM \n" +
                "  t0 \n" +
                "WHERE \n" +
                "  v1 IN (\n" +
                "    SELECT \n" +
                "      v2 \n" +
                "    FROM \n" +
                "      t0 \n" +
                "    WHERE \n" +
                "      (\n" +
                "        v2 IN (\n" +
                "          SELECT \n" +
                "            v1\n" +
                "          FROM \n" +
                "            t0\n" +
                "        ) \n" +
                "        OR (\n" +
                "          v2 IN (\n" +
                "            SELECT \n" +
                "              v1\n" +
                "            FROM \n" +
                "              t0\n" +
                "          )\n" +
                "        )\n" +
                "      ) \n" +
                "      AND (\n" +
                "        v3 IN (\n" +
                "          SELECT \n" +
                "            v1 \n" +
                "          FROM \n" +
                "            t0\n" +
                "        )\n" +
                "      )\n" +
                "  );";
        // check no exception
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" 10:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 5: v2 = 11: v1\n" +
                "  |  other predicates: (10: expr) OR (11: v1 IS NOT NULL)"));
    }

    @Test
    public void testJoinOutput() throws Exception {
        String sql = "select v1,v4 from t0, t1 where v2 = v5";
        String plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("output columns: 1, 4"));

        sql = "select v1+1,v4 from t0, t1 where v2 = v5";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("output columns: 1, 4"));

        sql = "select v2+1,v4 from t0, t1 where v2 = v5";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("output columns: 2, 4"));

        sql = "select v1+1,v4 from t0, t1 where v2 = v5 and v3 > v6";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("output columns: 1, 4"));

        sql = "select (v2+v6 = 1 or v2+v6 = 5) from t0, t1 where v2 = v5 ";
        plan = getVerboseExplain(sql);
        Assert.assertTrue(plan.contains("  4:Project\n" +
                "  |  output columns:\n" +
                "  |  7 <-> (8: add = 1) OR (8: add = 5)\n" +
                "  |  common expressions:\n" +
                "  |  8 <-> [2: v2, BIGINT, true] + [6: v6, BIGINT, true]\n" +
                "  |  cardinality: 1"));
        Assert.assertTrue(plan.contains("output columns: 2, 6"));

        sql = "select * from t0,t1 where v1 = v4";
        plan = getVerboseExplain(sql);
        Assert.assertFalse(plan.contains("output columns"));
    }

    @Test
    public void testSemiJoinReorderWithProject() throws Exception {
        String sql = "select x1.s1 from " +
                "(select t0.v1 + 1 as s1, t0.v2 from t0 left join t1 on t0.v2 = t1.v4) as x1 " +
                "left semi join t2 on x1.v2 = t2.v7";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  4:Project\n" +
                "  |  <slot 2> : 2: v2\n" +
                "  |  <slot 7> : 1: v1 + 1\n" +
                "  |  \n" +
                "  3:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN"));
    }

    @Test
    public void testCrossJoinOnPredicate() throws Exception {
        String sql = "select * from t0 cross join t1 on t0.v1 != t1.v4 and t0.v2 != t1.v5";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  3:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates: 1: v1 != 4: v4, 2: v2 != 5: v5"));
    }

    @Test
    public void testCrossJoinCastToInner() throws Exception {
        String sql = "select * from t0 cross join t1 on t0.v1 = t1.v4 and t0.v2 != t1.v5";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  3:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  other join predicates: 2: v2 != 5: v5"));
    }

    @Test
    public void testJoinPushBitmapCount() throws Exception {
        String sql = "SELECT 1 FROM t0 LEFT OUTER JOIN t1 ON t0.v1=t1.v4 " +
                "WHERE NOT CAST(bitmap_count(CASE WHEN t1.v4 in (10000) THEN bitmap_hash('abc') END) AS BOOLEAN)";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: LEFT OUTER JOIN (BROADCAST)"));
        Assert.assertTrue(plan.contains(
                "other predicates: NOT (CAST(bitmap_count(if(4: v4 = 10000, bitmap_hash('abc'), NULL)) AS BOOLEAN))"));
    }

    @Test
    public void testShuffleColumnsAdjustOrders() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select t0.v1, t1.v4, t2.v7 from t0 join[shuffle] t1 on t0.v1 = t1.v4 and t0.v2 = t1.v5 " +
                "join[shuffle] t2 on t0.v2 = t2.v8 and t0.v1 = t2.v7";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" 8:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 8: v8\n" +
                "  |  equal join conjunct: 1: v1 = 7: v7"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 03\n" +
                "    HASH_PARTITIONED: 5: v5, 4: v4"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 01\n" +
                "    HASH_PARTITIONED: 2: v2, 1: v1"));

        sql = "select t0.v1, t1.v4, t2.v7 from t0 join[shuffle] t1 on t0.v1 = t1.v4 and t0.v2 = t1.v5 " +
                "join[shuffle] t2 on t0.v2 = t2.v8 and t0.v1 = t2.v7 join[shuffle] t3 on t2.v7 = t3.v1 and t2.v8 = t3.v2 ";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("12:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 7: v7 = 10: v1\n" +
                "  |  equal join conjunct: 8: v8 = 11: v2"));
        Assert.assertTrue(plan.contains("8:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 8: v8\n" +
                "  |  equal join conjunct: 1: v1 = 7: v7"));
        Assert.assertTrue(plan.contains(" STREAM DATA SINK\n" +
                "    EXCHANGE ID: 07\n" +
                "    HASH_PARTITIONED: 7: v7, 8: v8"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 01\n" +
                "    HASH_PARTITIONED: 1: v1, 2: v2"));

        sql = "select t0.v1, t1.v4, t2.v7 from t0 join[shuffle] t1 on t0.v2 = t1.v5 and t0.v1 = t1.v4 " +
                "join[shuffle] t2 on t0.v2 = t2.v8 and t0.v1 = t2.v7 join[shuffle] t3 on t2.v7 = t3.v1 and t2.v8 = t3.v2 ";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" 8:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 8: v8\n" +
                "  |  equal join conjunct: 1: v1 = 7: v7"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 01\n" +
                "    HASH_PARTITIONED: 1: v1, 2: v2"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 03\n" +
                "    HASH_PARTITIONED: 4: v4, 5: v5"));

        sql = "select t0.v1, t1.v4, t2.v7 from t0 join[shuffle] t1 on t0.v2 = t1.v5 and t0.v1 = t1.v4 " +
                "join[shuffle] t2 on t0.v2 = t2.v8 and t0.v1 = t2.v7 join[shuffle] t3 on t0.v1 = t3.v1 and t0.v2 = t3.v2 ";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("12:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 10: v1\n" +
                "  |  equal join conjunct: 2: v2 = 11: v2"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 01\n" +
                "    HASH_PARTITIONED: 1: v1, 2: v2"));
        Assert.assertTrue(plan.contains(" STREAM DATA SINK\n" +
                "    EXCHANGE ID: 03\n" +
                "    HASH_PARTITIONED: 4: v4, 5: v5"));

        sql = "select * from t0 left join[shuffle] (\n" +
                "    select t1.* from t1 left join[shuffle] t2 \n" +
                "    on t1.v4 = t2.v7 \n" +
                "    and t1.v6 = t2.v9 \n" +
                "    and t1.v5 = t2.v8) as j2\n" +
                "on t0.v3 = j2.v6\n" +
                "  and t0.v1 = j2.v4\n" +
                "  and t0.v2 = j2.v5;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("8:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 3: v3 = 6: v6\n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  equal join conjunct: 2: v2 = 5: v5"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 03\n" +
                "    HASH_PARTITIONED: 6: v6, 4: v4, 5: v5"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 05\n" +
                "    HASH_PARTITIONED: 9: v9, 7: v7, 8: v8"));

        sql =
                "select a.v1, a.v4, b.v7, b.v1 from (select v1, v2, v4 from t0 join[shuffle] t1 on t0.v1 = t1.v4 and t0.v2 = t1.v5) a join[shuffle] " +
                        "(select v7, v8, v1 from t2 join[shuffle] t3 on t2.v7 = t3.v1 and t2.v8 = t3.v2) b " +
                        "on a.v2 = b.v8 and a.v1 = b.v7";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("12:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 8: v8\n" +
                "  |  equal join conjunct: 1: v1 = 7: v7"));
        Assert.assertTrue(plan.contains(" STREAM DATA SINK\n" +
                "    EXCHANGE ID: 09\n" +
                "    HASH_PARTITIONED: 11: v2, 10: v1"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 07\n" +
                "    HASH_PARTITIONED: 8: v8, 7: v7"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 03\n" +
                "    HASH_PARTITIONED: 5: v5, 4: v4"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 01\n" +
                "    HASH_PARTITIONED: 2: v2, 1: v1"));

        // check can not adjust column orders
        sql =
                "select a.v1, a.v4, b.v7, b.v1 from (select v1, v2, v4, v5 from t0 join[shuffle] t1 on t0.v1 = t1.v4 and t0.v2 = t1.v5) a join[shuffle] " +
                        "(select v7, v8, v1, v2 from t2 join[shuffle] t3 on t2.v7 = t3.v1 and t2.v8 = t3.v2) b " +
                        "on a.v2 = b.v8 and a.v4 = b.v8";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("14:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 8: v8\n" +
                "  |  equal join conjunct: 4: v4 = 8: v8"));
        Assert.assertTrue(plan.contains(" 11:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 7: v7 = 10: v1\n" +
                "  |  equal join conjunct: 8: v8 = 11: v2"));
        Assert.assertTrue(plan.contains(" STREAM DATA SINK\n" +
                "    EXCHANGE ID: 08\n" +
                "    HASH_PARTITIONED: 7: v7, 8: v8"));
        Assert.assertTrue(plan.contains("STREAM DATA SINK\n" +
                "    EXCHANGE ID: 10\n" +
                "    HASH_PARTITIONED: 10: v1, 11: v2"));

        // check can not adjust column orders
        sql =
                "select a.v1, a.v4, b.v7, b.v1 from (select v1, v2, v4, v5 from t0 join[shuffle] t1 on t0.v1 = t1.v4 and t0.v2 = t1.v5) a join[shuffle] " +
                        "(select v7, v8, v1, v2 from t2 join[shuffle] t3 on t2.v7 = t3.v1 and t2.v8 = t3.v2) b " +
                        "on a.v2 = b.v8 and a.v4 = b.v1";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("14:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: v2 = 8: v8\n" +
                "  |  equal join conjunct: 4: v4 = 10: v1"));
        Assert.assertTrue(plan.contains("11:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 7: v7 = 10: v1\n" +
                "  |  equal join conjunct: 8: v8 = 11: v2"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testJoinOnPredicateRewrite() throws Exception {
        String sql = "select * from t0 left outer join t1 on v1=v4 and cast(v2 as bigint) = v5 and false";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("equal join conjunct: 1: v1 = 4: v4"));
        Assert.assertTrue(plan.contains("1:EMPTYSET"));
    }

    @Test
    public void testSemiJoinFalsePredicate() throws Exception {
        String sql = "select * from t0 left semi join t3 on t0.v1 = t3.v1 " +
                "AND CASE WHEN NULL THEN t0.v1 ELSE '' END = CASE WHEN true THEN 'fGrak3iTt' WHEN false THEN t3.v1 ELSE 'asf' END";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  |  join op: RIGHT SEMI JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 4: v1 = 1: v1"));
    }

    @Test
    public void testJoinReorderWithPredicate() throws Exception {
        connectContext.getSessionVariable().setMaxTransformReorderJoins(2);
        String sql = "select t0.v1 from t0, t1, t2, t3 where t0.v1 + t3.v1 = 2";
        String plan = getFragmentPlan(sql);
        connectContext.getSessionVariable().setMaxTransformReorderJoins(4);
        Assert.assertTrue(plan.contains("11:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates: 1: v1 + 10: v1 = 2"));
    }

    @Test
    public void testOuterJoinBucketShuffle() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "SELECT DISTINCT t0.v1 FROM t0 RIGHT JOIN[BUCKET] t1 ON t0.v1 = t1.v4";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" 5:AGGREGATE (update serialize)\n" +
                "  |  STREAMING\n" +
                "  |  group by: 1: v1\n" +
                "  |  \n" +
                "  4:Project\n" +
                "  |  <slot 1> : 1: v1\n" +
                "  |  \n" +
                "  3:HASH JOIN\n" +
                "  |  join op: RIGHT OUTER JOIN (BUCKET_SHUFFLE)"));

        sql = "SELECT DISTINCT t0.v1 FROM t0 FULL JOIN[BUCKET] t1 ON t0.v1 = t1.v4";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("3:HASH JOIN\n" +
                "  |  join op: FULL OUTER JOIN (BUCKET_SHUFFLE)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v1 = 4: v4\n" +
                "  |  \n" +
                "  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0"));

        sql = "SELECT DISTINCT t1.v4 FROM t0 LEFT JOIN[BUCKET] t1 ON t0.v1 = t1.v4";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("7:AGGREGATE (merge finalize)\n" +
                "  |  group by: 4: v4\n" +
                "  |  \n" +
                "  6:EXCHANGE\n" +
                "\n" +
                "PLAN FRAGMENT 2\n" +
                " OUTPUT EXPRS:\n" +
                "  PARTITION: RANDOM\n" +
                "\n" +
                "  STREAM DATA SINK\n" +
                "    EXCHANGE ID: 06\n" +
                "    HASH_PARTITIONED: 4: v4\n" +
                "\n" +
                "  5:AGGREGATE (update serialize)\n" +
                "  |  STREAMING\n" +
                "  |  group by: 4: v4\n" +
                "  |  \n" +
                "  4:Project\n" +
                "  |  <slot 4> : 4: v4\n" +
                "  |  \n" +
                "  3:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BUCKET_SHUFFLE)"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testSemiJoinPredicateDerive() throws Exception {
        String sql = "select * from t0 left semi join t1 on v1 = v4 where v1 = 2";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  0:OlapScanNode\n" +
                "     TABLE: t0\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 1: v1 = 2"));
    }

    @Test
    public void testShuffleHashBucket() throws Exception {
        String sql = "SELECT COUNT(*)\n" +
                "FROM lineitem JOIN [shuffle] orders o1 ON l_orderkey = o1.o_orderkey\n" +
                "JOIN [shuffle] orders o2 ON l_orderkey = o2.o_orderkey";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (BUCKET_SHUFFLE(S))"));
    }

    @Test
    public void testShuffleHashBucket2() throws Exception {
        String sql = "select count(1) from lineitem t1 join [shuffle] orders t2 on " +
                "t1.l_orderkey = t2.o_orderkey and t2.O_ORDERDATE = t1.L_SHIPDATE join [shuffle] orders t3 " +
                "on t1.l_orderkey = t3.o_orderkey and t3.O_ORDERDATE = t1.L_SHIPDATE join [shuffle] orders t4 on\n" +
                "t1.l_orderkey = t4.o_orderkey and t4.O_ORDERDATE = t1.L_SHIPDATE;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("12:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))"));
        Assert.assertTrue(plan.contains("8:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BUCKET_SHUFFLE(S))"));
        Assert.assertTrue(plan.contains("4:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)"));
    }

    @Test
    public void testJoinReorderTakeEffect() throws Exception {
        Catalog catalog = connectContext.getCatalog();
        Database db = catalog.getDb("default_cluster:test");
        Table table = db.getTable("join2");
        OlapTable olapTable1 = (OlapTable) table;
        new Expectations(olapTable1) {
            {
                olapTable1.getRowCount();
                result = 2L;
                minTimes = 0;
            }
        };
        String sql = "select * from join1 join join2 on join1.id = join2.id;";
        String explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: INNER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id"));
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2"));
    }

    @Test
    public void testJoinReorderWithWithClause() throws Exception {
        connectContext.setDatabase("default_cluster:test");
        Catalog catalog = connectContext.getCatalog();
        Table table = catalog.getDb("default_cluster:test").getTable("join2");
        OlapTable olapTable1 = (OlapTable) table;
        new Expectations(olapTable1) {
            {
                olapTable1.getRowCount();
                result = 2L;
                minTimes = 0;
            }
        };
        String sql =
                "WITH t_temp AS (select join1.id as id1,  join2.id as id2 from join1 join join2 on join1.id = join2.id) select * from t_temp";
        String explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("equal join conjunct: 8: id = 11: id"));
        Assert.assertTrue(explainString.contains("  |----2:EXCHANGE\n" +
                "  |    \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: join1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON"));
    }

    // todo(ywb) disable replicate join temporarily
    public void testReplicatedJoin() throws Exception {
        connectContext.getSessionVariable().setEnableReplicationJoin(true);
        String sql = "select * from join1 join join2 on join1.id = join2.id;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (REPLICATED)"));
        Assert.assertFalse(plan.contains("EXCHANGE"));

        sql = "select * from join2 right join join1 on join1.id = join2.id;";
        plan = getFragmentPlan(sql);
        Assert.assertFalse(plan.contains("join op: INNER JOIN (REPLICATED)"));

        sql = "select * from join1 as a join (select sum(id),id from join2 group by id) as b on a.id = b.id;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (REPLICATED)"));
        Assert.assertFalse(plan.contains("EXCHANGE"));

        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        sql = "select * from join1 as a join (select sum(id),dt from join2 group by dt) as b on a.id = b.dt;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (BROADCAST)"));
        Assert.assertTrue(plan.contains("EXCHANGE"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);

        sql = "select a.* from join1 as a join join1 as b ;";
        plan = getFragmentPlan(sql);
        Assert.assertFalse(plan.contains("EXCHANGE"));

        sql = "select a.* from join1 as a join (select sum(id) from join1 group by dt) as b ;";
        plan = getFragmentPlan(sql);
        Assert.assertFalse(plan.contains("EXCHANGE"));

        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        sql = "select a.* from join1 as a join (select sum(id) from join1 group by dt) as b ;";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("EXCHANGE"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);

        connectContext.getSessionVariable().setEnableReplicationJoin(false);
    }

    @Test
    public void testReplicationJoinWithPartitionTable() throws Exception {
        connectContext.getSessionVariable().setEnableReplicationJoin(true);
        FeConstants.runningUnitTest = true;
        String sql = "select * from join1 join pushdown_test on join1.id = pushdown_test.k1;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("INNER JOIN (BROADCAST)"));
        FeConstants.runningUnitTest = false;
        connectContext.getSessionVariable().setEnableReplicationJoin(false);
    }

    // todo(ywb) disable replicate join temporarily
    public void testReplicationJoinWithEmptyNode() throws Exception {
        // check replicate join without exception
        connectContext.getSessionVariable().setEnableReplicationJoin(true);
        FeConstants.runningUnitTest = true;
        String sql = "with cross_join as (\n" +
                "  select * from \n" +
                "  (SELECT \n" +
                "      t0.v1, \n" +
                "      t0.v2, \n" +
                "      t0.v3\n" +
                "    FROM \n" +
                "      t0 \n" +
                "    WHERE \n" +
                "      false)\n" +
                "  subt0 LEFT SEMI \n" +
                "  JOIN \n" +
                "    (SELECT \n" +
                "      t2.v7, \n" +
                "      t2.v8, \n" +
                "      t2.v9\n" +
                "    FROM \n" +
                "      t2 \n" +
                "    WHERE \n" +
                "      false)\n" +
                "  subt2 ON subt0.v3 = subt2.v8, \n" +
                "  t1 \n" +
                ")\n" +
                "SELECT \n" +
                "  DISTINCT cross_join.v1 \n" +
                "FROM \n" +
                "  t0 LEFT JOIN\n" +
                "  cross_join\n" +
                "  ON cross_join.v4 = t0.v2;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("9:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (REPLICATED)"));
        FeConstants.runningUnitTest = false;
        connectContext.getSessionVariable().setEnableReplicationJoin(false);
    }

    @Test
    public void testColocateCoverReplicate() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select * from join1 join join1 as xx on join1.id = xx.id;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  |  join op: INNER JOIN (COLOCATE)\n"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testConstPredicateInRightJoin() throws Exception {
        String sql = "select * from test.join1 right join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        String explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  2:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 right semi join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 right anti join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 left join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  2:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 left semi join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 left anti join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 inner join test.join2 on join1.id = join2.id where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));

        sql = "select * from test.join1 where round(2.0, 0) > 3.0";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: round(2, 0) > 3"));
    }

    @Test
    public void testSelfColocateJoin() throws Exception {
        // single partition
        FeConstants.runningUnitTest = true;
        String queryStr = "select * from test.jointest t1, test.jointest t2 where t1.k1 = t2.k1";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        // multi partition
        queryStr = "select * from test.dynamic_partition t1, test.dynamic_partition t2 where t1.k1 = t2.k1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void TestSemiJoinNameResolve() {
        String sql = "select join1.dt from  test.join1 right semi join test.join2 on join1.id = join2.id";
        starRocksAssert.query(sql).analysisError("Column '`join1`.`dt`' cannot be resolved");

        sql = "select a.dt from test.join1 a left ANTI join test.join2 b on a.id = b.id " +
                "right ANTI join test.join2 d on a.id = d.id";
        starRocksAssert.query(sql).analysisError("Column '`a`.`dt`' cannot be resolved");
    }

    @Test
    public void testLeftOuterJoinOnOrPredicate() throws Exception {
        connectContext.setDatabase("default_cluster:test");

        String sql = "select * from join1 left join join2 on join1.id = join2.id\n" +
                "and (join2.id > 1 or join2.id < 10);";
        String explainString = getFragmentPlan(sql);

        Assert.assertTrue(explainString.contains("join op: LEFT OUTER JOIN (BROADCAST)"));
        Assert.assertTrue(explainString.contains("PREDICATES: (5: id > 1) OR (5: id < 10)"));
        Assert.assertTrue(explainString.contains("equal join conjunct: 2: id = 5: id"));
    }

    @Test
    public void testColocateJoin2() throws Exception {
        FeConstants.runningUnitTest = true;
        String queryStr =
                "select * from test.colocate1 t1, test.colocate2 t2 where t1.k1 = t2.k1 and t1.k2 = t2.k2 and t1.k3 = t2.k3";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 join (select k1, k2 from test.colocate2 group by k1, k2) t2 on t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 join (select k1, k2 from test.colocate2 group by k1, k2, k3) t2 on t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from (select k1, k2 from test.colocate1 group by k1, k2) t1 join (select k1, k2 from test.colocate2 group by k1, k2) t2 on t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: true"));

        queryStr =
                "select * from test.colocate1 t1 join [shuffle] test.colocate2 t2 on t1.k1 = t2.k1 and t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        // t1.k1 = t2.k2 not same order with distribute column
        queryStr =
                "select * from test.colocate1 t1, test.colocate2 t2 where t1.k1 = t2.k2 and t1.k2 = t2.k1 and t1.k3 = t2.k3";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr = "select * from test.colocate1 t1, test.colocate2 t2 where t1.k2 = t2.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("colocate: false"));

        queryStr = "select count(*) from test.colocate1 t1 group by t1.k1, t1.k2";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("1:AGGREGATE (update finalize)"));
        Assert.assertFalse(explainString.contains("3:AGGREGATE (merge finalize)"));

        queryStr = "select count(*) from test.colocate1 t1 group by t1.k1";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("1:AGGREGATE (update finalize)"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testOuterJoinEliminate() throws Exception {
        // test left join eliminate
        String sql = "select * from join1 left join join2 on join1.id = join2.id\n" +
                "where join2.id > 1;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test left join eliminate with compound predicate
        sql = "select * from join1 left join join2 on join1.id = join2.id\n" +
                "where join2.id > 1 or join2.id < 10 ;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: (5: id > 1) OR (5: id < 10)");

        // test left join eliminate with compound predicate
        sql = "select * from join1 left join join2 on join1.id = join2.id\n" +
                "where join2.id > 1 or join2.id is null;";
        //        getFragmentPlan(sql);
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id\n" +
                "  |  other predicates: (5: id > 1) OR (5: id IS NULL)");

        // test left join eliminate with inline view
        sql = "select * from join1 left join (select * from join2) b on join1.id = b.id\n" +
                "where b.id > 1;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test left join eliminate with inline view
        sql = "select * from (select * from join1) a left join (select * from join2) b on a.id = b.id\n" +
                "where b.id > 1;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test not left join eliminate
        sql = "select * from join1 left join join2 on join1.id = join2.id\n" +
                "where join2.id is null;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id\n" +
                "  |  other predicates: 5: id IS NULL");

        // test having group column
        sql = "select count(*) from join1 left join join2 on join1.id = join2.id\n" +
                "group by join2.id having join2.id > 1;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test having aggregate column
        sql = "select count(*) as count from join1 left join join2 on join1.id = join2.id\n" +
                "having count > 1;";
        starRocksAssert.query(sql).explainContains("7:AGGREGATE (merge finalize)\n" +
                        "  |  output: count(7: count)\n" +
                        "  |  group by: \n" +
                        "  |  having: 7: count > 1",
                "  3:HASH JOIN\n" +
                        "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id");

        // test right join eliminate
        sql = "select * from join1 right join join2 on join1.id = join2.id\n" +
                "where join1.id > 1;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test full outer join convert to left join
        sql = "select * from join1 full outer join join2 on join1.id = join2.id\n" +
                "where join1.id > 1;";
        starRocksAssert.query(sql).explainContains("  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        sql = "select * from join1 full outer join join2 on join1.id = join2.id and join1.dt != 2\n" +
                "where join1.id > 1;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id\n" +
                        "  |  other join predicates: 1: dt != 2",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test full outer join convert to right join
        sql = "select * from join1 full outer join join2 on join1.id = join2.id\n" +
                "where join2.id > 1;";
        starRocksAssert.query(sql).explainContains("  4:HASH JOIN\n" +
                        "  |  join op: RIGHT OUTER JOIN (PARTITIONED)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  2:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1");

        // test full outer join convert to inner join
        sql = "select * from join1 full outer join join2 on join1.id = join2.id\n" +
                "where join2.id > 1 and join1.id > 10;";
        starRocksAssert.query(sql).explainContains("  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  0:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 10",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1");

        // test multi left join eliminate
        sql = "select * from join1 left join join2 as b on join1.id = b.id\n" +
                "left join join2 as c on join1.id = c.id \n" +
                "where b.id > 1;";

        starRocksAssert.query(sql).explainContains("7:HASH JOIN\n" +
                        "  |  join op: LEFT OUTER JOIN (BUCKET_SHUFFLE(S))\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 8: id",
                "4:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (PARTITIONED)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 5: id = 2: id",
                "0:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 5: id > 1",
                "2:OlapScanNode\n" +
                        "     TABLE: join1\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 2: id > 1");

        sql = "select * from join1 left join join2 as b on join1.id = b.id\n" +
                "left join join2 as c on join1.id = c.id \n" +
                "where b.dt > 1 and c.dt > 1;";
        starRocksAssert.query(sql).explainContains("6:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 8: id",
                "  3:HASH JOIN\n" +
                        "  |  join op: INNER JOIN (BROADCAST)\n" +
                        "  |  hash predicates:\n" +
                        "  |  colocate: false, reason: \n" +
                        "  |  equal join conjunct: 2: id = 5: id",
                "  4:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 7: dt > 1",
                "  1:OlapScanNode\n" +
                        "     TABLE: join2\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 4: dt > 1");
    }

    @Test
    public void testJoinReorderWithReanalyze() throws Exception {
        Catalog catalog = connectContext.getCatalog();
        Table table = catalog.getDb("default_cluster:test").getTable("join2");
        OlapTable olapTable1 = (OlapTable) table;
        new Expectations(olapTable1) {
            {
                olapTable1.getRowCount();
                result = 2L;
                minTimes = 0;
            }
        };
        String sql = "select * from join1 join join2 on join1.id = join2.id and 1 < join1.id ";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));
        Assert.assertTrue(plan.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));
    }

    @Test
    public void testAntiJoinOnFalseConstantPredicate() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select join2.id from join1 RIGHT ANTI JOIN join2 on join1.id = join2.id" +
                " and 1 > 2 group by join2.id" +
                " union select join2.id from join1 RIGHT ANTI JOIN join2 on join1.id = join2.id " +
                " and 1 > 2 WHERE (NOT (true)) group by join2.id ";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("4:HASH JOIN\n" +
                "  |  join op: LEFT ANTI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 5: id = 2: id"));
        Assert.assertTrue(plan.contains("  2:EMPTYSET\n"));
        Assert.assertTrue(plan.contains("  8:EMPTYSET\n"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testFullOuterJoin2() throws Exception {
        String sql =
                "SELECT 1 FROM join1 RIGHT ANTI JOIN join2 on join1.id = join2.id and join2.dt = 1 FULL OUTER JOIN "
                        + "pushdown_test on join2.dt = pushdown_test.k3 WHERE join2.value != join2.value";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  9:HASH JOIN\n" +
                "  |  join op: RIGHT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 9: k3 = 4: dt"));
        Assert.assertTrue(plan.contains("  6:HASH JOIN\n" +
                "  |  join op: LEFT ANTI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 5: id = 2: id\n" +
                "  |  other join predicates: 4: dt = 1"));
    }

    @Test
    public void testFullOuterJoin3() throws Exception {
        String sql =
                "SELECT 1 FROM join1 RIGHT ANTI JOIN join2 on join1.id = join2.id FULL OUTER JOIN "
                        + "pushdown_test on join2.dt = pushdown_test.k3 WHERE join2.value != join2.value";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  9:HASH JOIN\n" +
                "  |  join op: RIGHT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 9: k3 = 4: dt"));
        Assert.assertTrue(plan.contains("  6:HASH JOIN\n" +
                "  |  join op: LEFT ANTI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 5: id = 2: id"));
    }

    @Test
    public void testJoinConst() throws Exception {
        String sql =
                "with user_info as (select 2 as user_id, 'mike' as user_name), address as (select 1 as user_id, 'newzland' as address_name) \n" +
                        "select * from address a right join user_info b on b.user_id=a.user_id;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("4:HASH JOIN\n" +
                "  |  join op: RIGHT OUTER JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 5: expr = 7: expr"));
        Assert.assertTrue(plan.contains("2:UNION\n" +
                "     constant exprs: \n" +
                "         2 | 'mike'"));
        Assert.assertTrue(plan.contains("0:UNION\n" +
                "     constant exprs: \n" +
                "         1 | 'newzland'"));
    }

    @Test
    public void testJoinPredicateTransitivity() throws Exception {
        // test left join : left table where binary predicate
        String sql = "select join1.id\n" +
                "from join1\n" +
                "left join join2 on join1.id = join2.id\n" +
                "where join1.id > 1;";
        String explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("PREDICATES: 2: id > 1"));
        Assert.assertTrue(explainString.contains("PREDICATES: 5: id > 1"));

        // test left join: left table where in predicate
        sql = "select join1.id\n" +
                "from join1\n" +
                "left join join2 on join1.id = join2.id\n" +
                "where join1.id in (2);";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("PREDICATES: 2: id = 2"));
        Assert.assertTrue(explainString.contains("PREDICATES: 5: id = 2"));

        // test left join: left table where between predicate
        sql = "select join1.id\n" +
                "from join1\n" +
                "left join join2 on join1.id = join2.id\n" +
                "where join1.id BETWEEN 1 AND 2;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("PREDICATES: 2: id >= 1, 2: id <= 2"));
        Assert.assertTrue(explainString.contains("PREDICATES: 5: id >= 1, 5: id <= 2"));

        // test left join: left table join predicate, left table couldn't push down
        sql = "select *\n from join1\n" +
                "left join join2 on join1.id = join2.id\n" +
                "and join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT OUTER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id\n" +
                "  |  other join predicates: 2: id > 1"));
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     partitions=0/1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        // test left join: right table where predicate.
        // If we eliminate outer join, we could push predicate down to join1 and join2.
        // Currently, we push predicate to join1 and keep join predicate for join2
        sql = "select *\n from join1\n" +
                "left join join2 on join1.id = join2.id\n" +
                "where join2.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        // test left join: right table join predicate, only push down right table
        sql = "select *\n from join1\n" +
                "left join join2 on join1.id = join2.id\n" +
                "and join2.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));
        Assert.assertTrue(explainString.contains("0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     partitions=0/1\n" +
                "     rollup: join1"));

        // test inner join: left table where predicate, both push down left table and right table
        sql = "select *\n from join1\n" +
                "join join2 on join1.id = join2.id\n" +
                "where join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        // test inner join: left table join predicate, both push down left table and right table
        sql = "select *\n from join1\n" +
                "join join2 on join1.id = join2.id\n" +
                "and join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        // test inner join: right table where predicate, both push down left table and right table
        sql = "select *\n from join1\n" +
                "join join2 on join1.id = join2.id\n" +
                "where join2.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        // test inner join: right table join predicate, both push down left table and right table
        sql = "select *\n from join1\n" +
                "join join2 on join1.id = join2.id\n" +
                "and 1 < join2.id;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        sql = "select *\n from join1\n" +
                "join join2 on join1.id = join2.value\n" +
                "and join2.value in ('abc');";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("equal join conjunct: 7: cast = 6: value"));
        Assert.assertTrue(explainString.contains("<slot 7> : CAST(2: id AS VARCHAR(1048576))"));
        Assert.assertTrue(explainString.contains("  2:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 6: value = 'abc'"));

        // test anti join, right table join predicate, only push to right table
        sql = "select *\n from join1\n" +
                "left anti join join2 on join1.id = join2.id\n" +
                "and join2.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT ANTI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id"));

        // test semi join, right table join predicate, only push to right table
        sql = "select *\n from join1\n" +
                "left semi join join2 on join1.id = join2.id\n" +
                "and join2.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT SEMI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id"));
        Assert.assertTrue(explainString.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: id > 1"));

        // test anti join, left table join predicate, left table couldn't push down
        sql = "select *\n from join1\n" +
                "left anti join join2 on join1.id = join2.id\n" +
                "and join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT ANTI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id\n" +
                "  |  other join predicates: 2: id > 1"));

        // test semi join, left table join predicate, only push to left table
        sql = "select *\n from join1\n" +
                "left semi join join2 on join1.id = join2.id\n" +
                "and join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT SEMI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id"));
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));

        // test anti join, left table where predicate, only push to left table
        sql = "select join1.id\n" +
                "from join1\n" +
                "left anti join join2 on join1.id = join2.id\n" +
                "where join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  4:HASH JOIN\n" +
                "  |  join op: RIGHT ANTI JOIN (PARTITIONED)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 5: id = 2: id"));
        Assert.assertTrue(explainString.contains("  2:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));

        // test semi join, left table where predicate, only push to left table
        sql = "select join1.id\n" +
                "from join1\n" +
                "left semi join join2 on join1.id = join2.id\n" +
                "where join1.id > 1;";
        explainString = getFragmentPlan(sql);
        Assert.assertTrue(explainString.contains("  3:HASH JOIN\n" +
                "  |  join op: LEFT SEMI JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 2: id = 5: id"));
        Assert.assertTrue(explainString.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 2: id > 1"));
    }

    @Test
    public void testJoinPredicateTransitivityWithSubqueryInWhereClause() throws Exception {
        String sql = "SELECT *\n" +
                "FROM test.pushdown_test\n" +
                "WHERE 0 < (\n" +
                "    SELECT MAX(k9)\n" +
                "    FROM test.pushdown_test);";
        starRocksAssert.query(sql).explainContains("  4:CROSS JOIN\n" +
                        "  |  cross join:\n" +
                        "  |  predicates is NULL",
                "  2:AGGREGATE (update finalize)\n" +
                        "  |  output: max(22: k9)\n" +
                        "  |  group by: \n" +
                        "  |  having: CAST(23: max AS DOUBLE) > 0.0");
    }

    @Test
    public void testPushDownEquivalenceDerivePredicate() throws Exception {
        // check is null predicate on t1.v5 which equivalences derive from t1.v4 can not push down to scan node
        String sql = "SELECT \n" +
                "  subt0.v2, \n" +
                "  t1.v6\n" +
                "FROM \n" +
                "  (\n" +
                "    SELECT \n" +
                "      t0.v1, \n" +
                "      t0.v2, \n" +
                "      t0.v3\n" +
                "    FROM \n" +
                "      t0\n" +
                "  ) subt0 \n" +
                "  LEFT JOIN t1 ON subt0.v3 = t1.v4 \n" +
                "  AND subt0.v3 = t1.v4 \n" +
                "  AND subt0.v3 = t1.v5 \n" +
                "  AND subt0.v3 >= t1.v5 \n" +
                "WHERE \n" +
                "  (\n" +
                "    (\n" +
                "      (t1.v4) < (\n" +
                "        (\n" +
                "          (-650850438)-(\n" +
                "            (\n" +
                "              (2000266938)%(-1243652117)\n" +
                "            )\n" +
                "          )\n" +
                "        )\n" +
                "      )\n" +
                "    ) IS NULL\n" +
                "  ) \n" +
                "GROUP BY \n" +
                " subt0.v2, \n" +
                "  t1.v6;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" 0:OlapScanNode\n" +
                "     TABLE: t1\n" +
                "     PREAGGREGATION: ON\n" +
                "     partitions=0/1"));
    }

    @Test
    public void testPredicateOnThreeTables() throws Exception {
        String sql = "SELECT \n" +
                "  DISTINCT t1.v4 \n" +
                "FROM \n" +
                "  t1, \n" +
                "  (\n" +
                "    SELECT \n" +
                "      t3.v1, \n" +
                "      t3.v2, \n" +
                "      t3.v3\n" +
                "    FROM \n" +
                "      t3\n" +
                "  ) subt3 FULL \n" +
                "  JOIN t0 ON subt3.v3 != t0.v1 \n" +
                "  AND subt3.v3 = t0.v1 \n" +
                "WHERE \n" +
                "  (\n" +
                "    (t0.v2) BETWEEN (\n" +
                "      CAST(subt3.v2 AS STRING)\n" +
                "    ) \n" +
                "    AND (t0.v2)\n" +
                "  ) = (t1.v4);";
        String plan = getFragmentPlan(sql);
        // check no exception
        Assert.assertTrue(plan.contains("HASH JOIN\n" +
                "  |  join op: INNER JOIN (BROADCAST)\n" +
                "  |  hash predicates:\n" +
                "  |  colocate: false, reason: \n" +
                "  |  equal join conjunct: 1: v4 = 10: cast"));
    }

    @Test
    public void testExplicitlyBroadcastJoin() throws Exception {
        String sql = "select * from db1.tbl1 join [BROADCAST] db1.tbl2 on tbl1.k1 = tbl2.k3";
        String plan = getFragmentPlan(sql);
        Assert.assertEquals(1, StringUtils.countMatches(plan, "INNER JOIN (BROADCAST)"));

        sql = "select * from db1.tbl1 join [SHUFFLE] db1.tbl2 on tbl1.k1 = tbl2.k3";
        plan = getFragmentPlan(sql);
        Assert.assertEquals(1, StringUtils.countMatches(plan, "INNER JOIN (PARTITIONED)"));
    }

    @Test
    public void testEquivalenceTest() throws Exception {
        String sql = "select * from t0 as x1 join t0 as x2 on x1.v2 = x2.v2 where x2.v2 = 'zxcv';";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: t0\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: CAST(2: v2 AS VARCHAR(1048576)) = 'zxcv'"));
        Assert.assertTrue(plan.contains("  1:OlapScanNode\n"
                + "     TABLE: t0\n"
                + "     PREAGGREGATION: ON\n"
                + "     PREDICATES: CAST(5: v2 AS VARCHAR(1048576)) = 'zxcv'\n"));
    }

    @Test
    public void testEquivalenceLoopDependency() throws Exception {
        String sql = "select * from t0 join t1 on t0.v1 = t1.v4 and cast(t0.v1 as STRING) = t0.v1";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("|  equal join conjunct: 1: v1 = 4: v4"));
        Assert.assertTrue(plan.contains("     TABLE: t0\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: CAST(1: v1 AS VARCHAR(65533)) = CAST(1: v1 AS VARCHAR(1048576))\n" +
                "     partitions=0/1\n"));
    }

    @Test
    public void testJoinCastFloat() throws Exception {
        String sql = "select * from t1, t3 right semi join test_all_type as a on t3.v1 = a.t1a and 1 > 2;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("equal join conjunct: 7: t1a = 17: cast"));
    }

    @Test
    public void testWherePredicatesToOnPredicate() throws Exception {
        String sql =
                "SELECT t0.v1 from t0 join test_all_type on t0.v2 = test_all_type.t1d where t0.v1 = test_all_type.t1d";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("join op: INNER JOIN"));
        Assert.assertTrue(planFragment.contains("  |  equal join conjunct: 2: v2 = 7: t1d\n"
                + "  |  equal join conjunct: 1: v1 = 7: t1d"));
    }

    @Test
    public void testDeriveOutputColumns() throws Exception {
        String sql = "select \n" +
                "  rand() as c0, \n" +
                "  round(\n" +
                "    cast(\n" +
                "      rand() as DOUBLE\n" +
                "    )\n" +
                "  ) as c1 \n" +
                "from \n" +
                "  (\n" +
                "    select \n" +
                "      subq_0.v1 as c0 \n" +
                "    from \n" +
                "      (\n" +
                "        select \n" +
                "          v1,v2,v3\n" +
                "        from \n" +
                "          t0 as ref_0 \n" +
                "        where \n" +
                "          ref_0.v1 = ref_0.v2 \n" +
                "        limit \n" +
                "          72\n" +
                "      ) as subq_0 \n" +
                "      right join t1 as ref_1 on (subq_0.v3 = ref_1.v5) \n" +
                "    where \n" +
                "      subq_0.v2 <> subq_0.v3 \n" +
                "    limit \n" +
                "      126\n" +
                "  ) as subq_1 \n" +
                "where \n" +
                "  66 <= unix_timestamp() \n" +
                "limit \n" +
                "  155;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("6:Project\n" +
                "  |  <slot 2> : 2: v2"));
    }

    @Test
    public void testEmptyTableDisableBucketJoin() throws Exception {
        String sql = "select colocate1.k1 from colocate1 join[bucket] test_agg on colocate1.k1 = test_agg.k1 and colocate1.k2 = test_agg.k2";
        String plan = getFragmentPlan(sql);
        assertContains(plan, " 4:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)");

        sql = "select colocate1.k1 from colocate1 join[bucket] colocate2 on colocate1.k1 = colocate2.k2 and colocate1.k2 = colocate2.k3";
        plan = getFragmentPlan(sql);
        assertContains(plan, "4:HASH JOIN\n" +
                "  |  join op: INNER JOIN (PARTITIONED)");
    }
}
