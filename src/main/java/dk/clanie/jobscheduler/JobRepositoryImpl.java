/*
 * Copyright (C) 2025, Claus Nielsen, clausn999@gmail.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package dk.clanie.jobscheduler;

import static dk.clanie.core.Utils.opt;
import static dk.clanie.mongo.MongoDbUtils.criteriaAndContains;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JobRepositoryImpl implements JobRepositoryCustom {

	private final MongoTemplate mongo;


	@Override
	public List<Job> find(UUID tenantId, Pageable pageable, JobFilter filter) {
		return mongo.find(query(filterCriteria(tenantId, filter)).with(pageable), Job.class);
	}


	@Override
	public List<UUID> findIds(UUID tenantId, JobFilter filter) {
		return mongo.findDistinct(query(filterCriteria(tenantId, filter)), "id", Job.class, UUID.class);
	}


	@Override
	public long count(UUID tenantId, JobFilter filter) {
		return mongo.count(query(filterCriteria(tenantId, filter)), Job.class);
	}


	private Criteria filterCriteria(UUID tenantId, JobFilter filter) {
		Criteria criteria = where("tenantId").is(tenantId);
		criteria = criteriaAndContains(criteria, List.of("description", "isin", "symbol"), filter.getMatch());
		if (isTrue(filter.getExcludeDisabled())) {
			criteria = criteria
					.and("configEnabled").is(true)
					.and("userEnabled").is(true);
		}
		return criteria;
	}


	@Override
	public Optional<ZonedDateTime> findNextExecutionTime() {
		Query query = nextToSchedule(null);
		query.fields().include("nextExecution");
		return opt(mongo.findOne(query, Job.class)).map(Job::getNextExecution);
	}


	@Override
	public Optional<Job> popForExecution() {
		UUID jobExecutionId = UUID.randomUUID();
		Query query = nextToSchedule(criteria -> criteria.and("nextExecution").lte(ZonedDateTime.now()));
		Job job = mongo.findAndModify(query, new Update()
				.set("jobExecutionId", jobExecutionId)
				.currentDate("poppedForExecution"),
				new FindAndModifyOptions().returnNew(true),
				Job.class);
		return opt(job);
	}


	/**
	 * Builds a query to select the Job that is the next to be scheduled.
	 * <p>
	 * There is a matching partial index defined on Job.
	 * 
	 * @param criteriaConsumer optional consumer to add additional criteria
	 * @return the query
	 */
	private Query nextToSchedule(Consumer<Criteria> criteriaConsumer) {
		Criteria criteria = where("configEnabled").is(true)
				.and("userEnabled").is(true)
				.and("jobExecutionId").isNull();
		if (criteriaConsumer != null) criteriaConsumer.accept(criteria);
		return query(criteria)
				.with(Sort.by("nextExecution"))
				.limit(1);
	}


}
