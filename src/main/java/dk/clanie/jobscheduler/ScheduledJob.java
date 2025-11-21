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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.scheduling.support.CronExpression;

/**
 * Defines how JobScheduler should schedule execution of a method.
 * 
 * At most one of {@link #cron()}, {@link #delay()}, or {@link #rate()} may be specified.
 * If none of them are, the method will not be scheduled automatically, but it can still
 * be invoked or scheduled manually.
 * <p>
 * When using this annotation, the annotated method must be a Spring bean
 * method, and you must also add a configuration property named
 * {@code jobScheduler.job.<beanName>.<methodName>.enabled},
 * so the job can be enabled or disabled in the configuration.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScheduledJob {


	/**
	 * A cron-like expression, extending the usual UN*X definition to include triggers
	 * on the second, minute, hour, day of month, month, and day of week.
	 * <p>For example, {@code "0 * * * * MON-FRI"} means once per minute on weekdays
	 * (at the top of the minute - the 0th second).
	 * 
	 * @see CronExpression#parse(String)
	 */
	String cron() default "";


	/**
	 * Execute the annotated method with a fixed period between the end of the
	 * last invocation and the start of the next.
	 * 
	 * Delay must be given as a ISO-8601 duration (PnDTnHnMnS).
	 */
	String delay() default "";


	/**
	 * Execute the annotated method with a fixed period between invocations.
	 * 
	 * Rate must be given as a ISO-8601 duration (PnDTnHnMnS).
	 */
	String rate() default "";


}
