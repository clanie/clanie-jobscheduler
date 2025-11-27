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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface JobExecutionRepository extends MongoRepository<JobExecution, UUID> {

	@Query("{ tenantId: ?0, jobId: ?1 }")
	List<JobExecution> findByJobId(UUID tenantId, UUID jobId, Pageable pageable);

	@Query("{ tenantId: ?0, jobExecutionId: ?1 }")
	Optional<JobExecution> findByJobExecutionId(UUID tenantId, UUID jobExecutionId);

	@Query("{ tenantId: ?0 }")
	Page<JobExecution> findByTenantId(UUID tenantId, Pageable pageable);

	@Query("{ tenantId: ?0, success: ?1 }")
	Page<JobExecution> findByTenantIdAndSuccess(UUID tenantId, boolean success, Pageable pageable);

}
