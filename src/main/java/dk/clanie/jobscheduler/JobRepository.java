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

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface JobRepository extends JobRepositoryCustom, MongoRepository<Job, UUID> {


	@Query("{ tenantId: ?0, id: { $in: ?1 } }")
	List<Job> findByIdIn(UUID tenantId, Collection<UUID> jobIds);


	@Query("{ tenantId: ?0, _id: ?1 }")
	@Update("{ $currentDate: { lastExecution: true } }")
	void registerExecuted(UUID tenantId, UUID jobId);


	@Query(value = "{}", fields = "{ bean: '$name.bean', method: '$name.method', _id: 0 }")
	Set<JobName> findNames();


	@Query(value = "{ name: {$in: ?0}}", fields = "{ bean: '$name.bean', method: '$name.method', _id: 0 }")
	Set<JobName> findNamesByNameIn(Collection<JobName> names);


	@Query(value = "{ name: { $in: ?0 }, configEnabled: { $eq: true } }")
	List<Job> findConfigEnabledJobsByNameIn(Set<JobName> obsoleteJobNames);


	@Query(value = "{ name: { $in: ?0 }, configEnabled: { $ne: true } }")
	List<Job> findConfigDisabledJobsByNameIn(Set<JobName> jobNamesEnabledInConfig);


	@Query(value = "{ tenantId: ?0, _id: ?1 }", delete = true)
	void deleteById(UUID tenantId, UUID jobId);


	@Query(value = "{ tenantId: ?0, _id: ?1 }")
	@Update("{ $set: { userEnabled: ?2 } }")
	int setUserEnabled(UUID tenantId, UUID id, boolean userEnabled);


	@Query(value = "{ tenantId: ?0, _id: ?1, batchId: null }")
	@Update("{ $set: { nextExecution: ?2 } }")
	int setNextExecution(UUID tenantId, UUID id, ZonedDateTime zonedDateTime);


}
