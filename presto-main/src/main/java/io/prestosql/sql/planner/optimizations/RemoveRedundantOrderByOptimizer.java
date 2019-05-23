/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner.optimizations;

import io.airlift.log.Logger;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.ChildReplacer;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.SortNode;

import java.util.ArrayList;
import java.util.List;

import static io.prestosql.sql.planner.plan.SimplePlanRewriter.rewriteWith;

public class RemoveRedundantOrderByOptimizer
        implements PlanOptimizer
{
    private static final Logger logger = Logger.get(RemoveRedundantOrderByOptimizer.class);

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        OrderByContext orderByContext = new OrderByContext(true);
        return rewriteWith(new Rewriter(), plan, orderByContext);
    }

    private class Rewriter
            extends SimplePlanRewriter<OrderByContext>
    {
        @Override
        public PlanNode visitOutput(OutputNode node, RewriteContext<OrderByContext> context)
        {
            //Setting orderingUsedAbove to true
            return context.defaultRewrite(node, new OrderByContext(true));
        }

        protected PlanNode visitPlan(PlanNode node, RewriteContext<OrderByContext> context)
        {
            boolean orderingUsedAbove = context.get().isOrderingUsedAbove();
            if (node instanceof JoinNode || node instanceof AggregationNode || node instanceof LimitNode) {
                orderingUsedAbove = false;
                context.defaultRewrite(node, new OrderByContext(false));
            }
            else {
                context.defaultRewrite(node, context.get());
            }

            if (!orderingUsedAbove && !node.getSources().isEmpty()) {
                List<PlanNode> newChildren = null;
                for (int i = 0; i < node.getSources().size(); i++) {
                    if (node.getSources().get(i) instanceof SortNode) {
                        if (newChildren == null) {
                            newChildren = new ArrayList(node.getSources());
                        }
                        newChildren.set(i, ((SortNode) node.getSources().get(i)).getSource());
                    }
                }
                if (newChildren != null) {
                    return ChildReplacer.replaceChildren(node, newChildren);
                }
            }
            return node;
        }
    }

    private class OrderByContext
    {
        boolean orderingUsedAbove;

        public OrderByContext(boolean orderingUsedAbove)
        {
            this.orderingUsedAbove = orderingUsedAbove;
        }

        public boolean isOrderingUsedAbove()
        {
            return orderingUsedAbove;
        }

        public OrderByContext setOrderingUsedAbove(boolean orderingUsedAbove)
        {
            this.orderingUsedAbove = orderingUsedAbove;
            return this;
        }
    }
}
