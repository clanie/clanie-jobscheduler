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

import static lombok.AccessLevel.PRIVATE;

import java.util.UUID;

import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import dk.clanie.mongo.entity.AbstractTenantEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder()
@SuppressFBWarnings(value = "NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "factory method initializes fields - default ctor is only for Spring / Mongo")
@NoArgsConstructor(access = PRIVATE) // For Spring / Mongo
@AllArgsConstructor
@Document(collection = JobExecution.COLLECTION_NAME)
@CompoundIndexes({
	@CompoundIndex(def = "{jobId: 1, createdDate: -1}"),
	@CompoundIndex(def = "{createdDate: -1}"),
})
public class JobExecution extends AbstractTenantEntity {

	public static final String COLLECTION_NAME = "jobExecutions";

	/**
	 * The id of the Job that was executed.
	 */
	private UUID jobId;

	/**
	 * Indicates whether the job execution was successful.
	 */
	private boolean success;

	/**
	 * Optional error message if the job failed.
	 */
	private String stackTrace;


	private JobExecution(UUID tenantId, UUID id, UUID jobId, boolean success, String stackTrace) {
		setId(id);
		setTenantId(tenantId);
		this.jobId = jobId;
		this.success = success;
		this.stackTrace = stackTrace;
	}

	/**
	 * Creates a JobExecution instance from a Job and execution result.
	 * <p>
	 * The JobExecution will inherit the tenant ID from the job, and use the job's
	 * jobExecutionId as its own ID, so it is important that this is called before
	 * the jobExecutionId is reset on the Job.
	 * 
	 * @param job the job that was executed
	 * @param success whether the job execution was successful
	 * @param stackTrace optional error message or stack trace if the job failed
	 * @return a new JobExecution instance
	 */
	public static JobExecution of(Job job, boolean success, String stackTrace) {
		return new JobExecution(
				job.getTenantId(),
				job.getJobExecutionId(),
				job.getId(),
				success,
				stackTrace
				);
	}


}
