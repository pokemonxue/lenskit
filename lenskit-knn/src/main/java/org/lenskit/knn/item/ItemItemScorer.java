/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.knn.item;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongIterators;
import org.grouplens.lenskit.data.history.UserHistorySummarizer;
import org.grouplens.lenskit.symbols.Symbol;
import org.grouplens.lenskit.transform.normalize.UserVectorNormalizer;
import org.grouplens.lenskit.transform.normalize.VectorTransformation;
import org.grouplens.lenskit.util.ScoredItemAccumulator;
import org.grouplens.lenskit.util.TopNScoredItemAccumulator;
import org.grouplens.lenskit.util.UnlimitedScoredItemAccumulator;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.grouplens.lenskit.vectors.SparseVector;
import org.grouplens.lenskit.vectors.VectorEntry;
import org.lenskit.api.ResultMap;
import org.lenskit.basic.AbstractItemScorer;
import org.lenskit.data.dao.UserEventDAO;
import org.lenskit.data.events.Event;
import org.lenskit.data.history.History;
import org.lenskit.data.history.UserHistory;
import org.lenskit.knn.MinNeighbors;
import org.lenskit.knn.NeighborhoodSize;
import org.lenskit.knn.item.model.ItemItemModel;
import org.lenskit.results.Results;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Score items using an item-item CF model. User ratings are <b>not</b> supplied
 * as default preferences.
 */
public class ItemItemScorer extends AbstractItemScorer {
    private static final Logger logger = LoggerFactory.getLogger(ItemItemScorer.class);
    public static final Symbol NEIGHBORHOOD_SIZE_SYMBOL =
            Symbol.of("org.grouplens.lenskit.knn.item.neighborhoodSize");
    protected final ItemItemModel model;

    private final UserEventDAO dao;
    @Nonnull
    protected final UserVectorNormalizer normalizer;
    protected final UserHistorySummarizer summarizer;
    @Nonnull
    protected final NeighborhoodScorer scorer;
    private final int neighborhoodSize;
    private final int minNeighbors;

    /**
     * Construct a new item-item scorer.
     *
     * @param dao    The DAO.
     * @param m      The model
     * @param sum    The history summarizer.
     * @param scorer The neighborhood scorer.
     * @param nnbrs  The number of neighbors.
     * @param min    The minimum number of neighbors.
     */
    @Inject
    public ItemItemScorer(UserEventDAO dao, ItemItemModel m,
                          UserHistorySummarizer sum,
                          NeighborhoodScorer scorer,
                          UserVectorNormalizer norm,
                          @NeighborhoodSize int nnbrs,
                          @MinNeighbors int min) {
        this.dao = dao;
        model = m;
        summarizer = sum;
        this.scorer = scorer;
        normalizer = norm;
        neighborhoodSize = nnbrs;
        minNeighbors = min;
        logger.debug("configured item-item scorer with scorer {}", scorer);
    }

    @Nonnull
    public UserVectorNormalizer getNormalizer() {
        return normalizer;
    }

    @Nonnull
    @Override
    public Map<Long, Double> score(long user, @Nonnull Collection<Long> items) {
        logger.debug("scoring {} items for user {}", items.size(), user);
        Long2DoubleMap results = new Long2DoubleOpenHashMap(items.size());
        ItemItemScoreAccumulator accum = ItemItemScoreAccumulator.basic(results);

        scoreItems(user, items, accum);

        return results;
    }

    /**
     * Score items by computing predicted ratings.
     */
    @Nonnull
    @Override
    public ResultMap scoreWithDetails(long user, @Nonnull Collection<Long> items) {
        logger.debug("scoring {} items for user {} with details", items.size(), user);
        List<ItemItemResult> results = new ArrayList<>(items.size());
        ItemItemScoreAccumulator accum = ItemItemScoreAccumulator.detailed(results);

        scoreItems(user, items, accum);

        return Results.newResultMap(results);
    }

    /**
     * Score all items into an accumulator.
     * @param user The user.
     * @param items The items to score.
     * @param accum The accumulator.
     */
    private void scoreItems(long user, @Nonnull Collection<Long> items, ItemItemScoreAccumulator accum) {
        UserHistory<? extends Event> history = dao.getEventsForUser(user, summarizer.eventTypeWanted());
        if (history == null) {
            history = History.forUser(user);
        }

        SparseVector summary = summarizer.summarize(history);
        logger.trace("user has {} ratings", summary.size());
        VectorTransformation transform = normalizer.makeTransformation(user, summary);
        MutableSparseVector normed = summary.mutableCopy();
        transform.apply(normed);
        Long2DoubleMap itemScores = normed.asMap();

        LongIterator iter = LongIterators.asLongIterator(items.iterator());
        while (iter.hasNext()) {
            final long item = iter.nextLong();
            scoreItem(itemScores, item, accum);
        }

        accum.applyReversedTransform(transform);
    }

    protected void scoreItem(Long2DoubleMap userData, long item, ItemItemScoreAccumulator accum) {
        SparseVector allNeighbors = model.getNeighbors(item);
        ScoredItemAccumulator acc;
        if (neighborhoodSize > 0) {
            // FIXME Abstract accumulator selection logic
            acc = new TopNScoredItemAccumulator(neighborhoodSize);
        } else {
            acc = new UnlimitedScoredItemAccumulator();
        }

        for (VectorEntry e: allNeighbors) {
            if (userData.containsKey(e.getKey())) {
                acc.put(e.getKey(), e.getValue());
            }
        }

        Long2DoubleMap neighborhood = acc.finishMap();
        assert neighborhoodSize <= 0 || neighborhood.size() <= neighborhoodSize;
        if (neighborhood.size() < minNeighbors) {
            return;
        }
        logger.trace("scoring item {} with {} of {} neighbors",
                     item, neighborhood.size(), allNeighbors.size());
        scorer.score(item, neighborhood, userData, accum);
        // logger.trace("computed score {}", score);
    }
}
