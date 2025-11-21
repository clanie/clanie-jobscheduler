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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Processes @ScheduledJob annotated beans, creating Jobs for them if they are not already there.
 * 
 * Also enables / disables jobs according to configuration properties named {@code jobScheduler.job.<beanName>.<methodName>.enabled}.
 */
public class JobInitializer {


	@Autowired
	private JobService jobService;

	@Autowired(required = false)
	private JobInitializationLatch initializationLatch;


	@EventListener
	public void handleContextRefresh(ContextRefreshedEvent event) {
		try {
			jobService.scanForJobs();
		} finally {
			opt(initializationLatch).ifPresent(JobInitializationLatch::countDown);
		}
	}


}
