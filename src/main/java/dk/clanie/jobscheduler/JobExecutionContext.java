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

/**
 * Carries per-execution flags for the current job invocation.
 * Set by {@link JobExecutionService} before invoking the job method; readable by jobs.
 */
public class JobExecutionContext {

	private static final ThreadLocal<Boolean> SUPPRESS = new ThreadLocal<>();

	static void set(boolean suppress) { SUPPRESS.set(suppress); }
	static void clear() { SUPPRESS.remove(); }

	/** True when the job's maxFailureDuration has not yet been exceeded. */
	public static boolean isSuppressFailureLogging() {
		return Boolean.TRUE.equals(SUPPRESS.get());
	}

}
