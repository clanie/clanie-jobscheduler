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

import java.time.ZonedDateTime;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import dk.clanie.mongo.entity.AbstractTenantEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder()
@SuppressFBWarnings(value = "NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "factory method initializes fields - default ctor is only for Spring / Mongo")
@NoArgsConstructor(access = PRIVATE) // For Spring / Mongo
@Document(collection = Job.COLLECTION_NAME)
@TypeAlias("Job")
@CompoundIndexes({
	@CompoundIndex(def = "{nextExecution: 1}", unique = false, partialFilter = "{configEnabled: true, userEnabled: true, jobExecutionId: null}"),
	@CompoundIndex(def = "{applicationName: 1, name: 1}", unique = true),
})
public class Job extends AbstractTenantEntity {

	public static final String COLLECTION_NAME = "jobs";

	private String applicationName;

	private JobName name;

	@NonNull
	private JobSchedule schedule;

	/**
	 * Enabled in configuration.
	 * 
	 * A Job is by default both configEnabled and userEnabled.
	 * If either is false, the Job will not be executed.
	 */
	private boolean configEnabled;

	/**
	 * Enabled by user.
	 * 
	 * A Job is by default both configEnabled and userEnabled.
	 * If either is false, the Job will not be executed.
	 */
	private boolean userEnabled;

	private ZonedDateTime nextExecution;

	/**
	 * The time the job started executing.
	 * 
	 * It is removed again when the job execution is finished.
	 */
	private ZonedDateTime poppedForExecution;

	/**
	 * A random id assigned when the job is popped for execution.
	 * 
	 * It is removed again when the job execution is finished.
	 */
	private UUID jobExecutionId;

	private long executionCount;
	private ZonedDateTime lastSuccessfullyExecuted;
	private ZonedDateTime lastFailedExecution;


	public Job(UUID tenantId, String applicationName, JobName name, JobSchedule schedule) {
		setTenantId(tenantId);
		setId( UUID.randomUUID());
		this.applicationName = applicationName;
		this.name = name;
		this.schedule = schedule;
		this.configEnabled = true;
		this.userEnabled = true;
		this.nextExecution = schedule.calculateNextExecution(this);
	}


	// This is a hack to prevent access to the builder inherited from TenantEntity
	@SuppressWarnings("unused")
	private static Job.JobBuilder<?, ?> builder() {
		throw new UnsupportedOperationException("Builder is disabled for Job");
	}




	public void registerCompletedSuccessfully() {
		updateAfterExecution();
		lastSuccessfullyExecuted = ZonedDateTime.now();
	}


	public void registerFailed() {
		updateAfterExecution();
		lastFailedExecution = ZonedDateTime.now();
	}


	private void updateAfterExecution() {
		poppedForExecution = null;
		jobExecutionId = null;
		executionCount++;
		nextExecution = schedule.calculateNextExecution(this);
	}


}
