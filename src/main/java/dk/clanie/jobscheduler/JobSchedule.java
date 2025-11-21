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

import java.time.Duration;
import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import dk.clanie.jobscheduler.JobSchedule.Cron;
import dk.clanie.jobscheduler.JobSchedule.Delay;
import dk.clanie.jobscheduler.JobSchedule.Manual;
import dk.clanie.jobscheduler.JobSchedule.Rate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@JsonTypeInfo(
		use = JsonTypeInfo.Id.NAME,
		include = JsonTypeInfo.As.EXISTING_PROPERTY,
		property = "type",
		visible = true
		)
@JsonSubTypes({
	@JsonSubTypes.Type(value = Cron.class, name = "cron"),
	@JsonSubTypes.Type(value = Delay.class, name = "delay"),
	@JsonSubTypes.Type(value = Rate.class, name = "rate"),
	@JsonSubTypes.Type(value = Manual.class, name = "manual")
})
@Getter
@NoArgsConstructor(access = PRIVATE) // For Spring / MongoDB
public abstract sealed class JobSchedule permits Cron, Delay, Rate, Manual {


	protected String type;

	/**
	 * Calculate when to schedule the next execution of the job.
	 * 
	 * This is called when the job is first created, and <b>after</b> each execution, so schedulers will
	 * never be given the chance to schedule parallel executions of the same Job.
	 */
	protected abstract ZonedDateTime calculateNextExecution(Job job);


	/**
	 * Chron-like scheduling.
	 * <p/>
	 * Will not schedule multiple instances of a job to run in parallel, so even if the cron expression
	 * would trigger the job eg. every minute, it will only be scheduled when the previous instance has
	 * finished.
	 *
	 * @see CronExpression#parse(String)
	 */
	@JsonTypeName("cron")
	@Getter
	@ToString
	@NoArgsConstructor(access = PRIVATE) // For Spring / MongoDB
	public static final class Cron extends JobSchedule {

		private String cron;

		@JsonCreator
		protected Cron(@JsonProperty("cron") String cron) {
			type = "cron";
			this.cron = cron;
		}

		@Override
		protected ZonedDateTime calculateNextExecution(Job job) {
			return CronExpression.parse(cron).next(ZonedDateTime.now());
		}

	}


	/**
	 * Schedules invocations with a fixed period between the end of the last invocation and the start
	 * of the next.
	 */
	@JsonTypeName("delay")
	@Getter
	@ToString
	@NoArgsConstructor(access = PRIVATE) // For Spring / MongoDB
	public static final class Delay extends JobSchedule {

		private Duration delay;

		@JsonCreator
		protected Delay(@JsonProperty("delay") Duration delay) {
			type = "delay";
			this.delay = delay;
		}

		@Override
		protected ZonedDateTime calculateNextExecution(Job job) {
			return ZonedDateTime.now().plus(delay);
		}

	}


	/**
	 * Schedules with a fixed period between invocations.
	 * <p/>
	 * Will not schedule multiple instances of a job to run in parallel, so invocations will be
	 * skipped if jobs run for longer the the invocation interval (rate).
	 */
	@JsonTypeName("rate")
	@Getter
	@ToString
	@NoArgsConstructor(access = PRIVATE) // For Spring / MongoDB
	public static final class Rate extends JobSchedule {

		private Duration rate;
		private ZonedDateTime firstExecution;

		@JsonCreator
		protected Rate(@JsonProperty("rate") Duration rate,
				@JsonProperty("firstExecution") ZonedDateTime firstExecution) {
			type = "rate";
			this.rate = rate;
			this.firstExecution = firstExecution;
		}

		@Override
		protected ZonedDateTime calculateNextExecution(Job job) {
			ZonedDateTime now = ZonedDateTime.now();

			// If current time is before notBefore (ie. first execution time), return that;
			if (firstExecution == null) {
				firstExecution = now;
				return firstExecution;
			} else { // else calculate next execution time based on rate.
				Duration elapsed = Duration.between(firstExecution, now);
				long intervalsPassed = elapsed.dividedBy(rate);
				return firstExecution.plus(rate.multipliedBy(intervalsPassed + 1));
			}
		}

	}


	/**
	 * Manual scheduling.
	 */
	@JsonTypeName("manual")
	@Getter
	@ToString
	public static final class Manual extends JobSchedule {

		@JsonCreator
		protected Manual() {
			type = "manual";
		}

		@Override
		protected ZonedDateTime calculateNextExecution(Job job) {
			return null;
		}

	}


	public static Cron cron(String cron) {
		return new Cron(cron);
	}


	public static Delay delay(Duration delay) {
		return new Delay(delay);
	}


	public static Rate rate(Duration rate) {
		return new Rate(rate, null);
	}


	public static Manual manual() {
		return new Manual();
	}


}
