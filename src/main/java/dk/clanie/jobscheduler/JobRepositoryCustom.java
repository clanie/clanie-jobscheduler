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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

public interface JobRepositoryCustom {


	/**
	 * Finds jobs for a given tenant with optional filtering and pagination.
	 * 
	 * @param tenantId the tenant identifier
	 * @param pageable pagination information
	 * @param filter filter criteria for matching jobs
	 * @return list of jobs matching the criteria
	 */
	List<Job> find(UUID tenantId, Pageable pageable, JobFilter filter);


	/**
	 * Finds job IDs for a given tenant with optional filtering.
	 * 
	 * @param tenantId the tenant identifier
	 * @param filter filter criteria for matching jobs
	 * @return list of job IDs matching the criteria
	 */
	List<UUID> findIds(UUID tenantId, JobFilter filter);


	/**
	 * Counts jobs for a given tenant with optional filtering.
	 * 
	 * @param tenantId the tenant identifier
	 * @param filter filter criteria for matching jobs
	 * @return number of jobs matching the criteria
	 */
	long count(UUID tenantId, JobFilter filter);


	/**
	 * Finds the next scheduled execution time among all enabled jobs.
	 * 
	 * @return the next execution time, or empty if no jobs are scheduled
	 */
	Optional<ZonedDateTime> findNextExecutionTime();


	/**
	 * Atomically retrieves and marks the next job ready for execution.
	 * <p>
	 * This method finds the next enabled job that is due for execution (nextExecution &lt;= now)
	 * and atomically sets its jobExecutionId and poppedForExecution timestamp.
	 * 
	 * @return the job ready for execution, or empty if no job is due
	 */
	Optional<Job> popForExecution();


}
