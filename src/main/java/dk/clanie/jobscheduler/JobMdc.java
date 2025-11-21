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

import static dk.clanie.core.Utils.asString;

import java.util.UUID;

import org.slf4j.MDC;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum JobMdc {


	JOB_ID("jobId"),
	JOB_NAME("jobName");


	private final String key;


	public static void applyAndRun(String jobName, Runnable runnable) {
		try {
			setJobMdc(jobName);
			runnable.run();
		} finally {
			clear();
		}
	}


	private static void setJobMdc(String jobName) {
		String jobId = asString(UUID.randomUUID());
		MDC.put(JOB_ID.key, jobId);
		MDC.put(JOB_NAME.key, jobName);
	}


	private static void clear() {
		MDC.remove(JOB_ID.key);
		MDC.remove(JOB_NAME.key);
	}


}
