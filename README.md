# Job Scheduler

Configuration properties:

```
jobScheduler:
  enabled: true
  maxParallelJobs: 2
  pollInterval: PT1M
  exitWhenIdle: true
  shutdownTimeout: PT10S
```

At shutdown the scheduler stops polling as soon as the application context starts closing,
and gives jobs already running `shutdownTimeout` to finish before interrupting them. Both
happen before the Mongo client is closed. Keep `shutdownTimeout` plus Spring's
`spring.lifecycle.timeout-per-shutdown-phase` below the container's stop timeout.


## Jobs and Scheduling

Scheduled jobs are defined using the `@ScheduledJob` annotation.

Jobs are automatically created in Mongo for any NEWLY annotated methods, and Jobs
which no longer match an annotated bean method are automatically disabled.

But notice that existing Jobs are not automatically updated, because the annotation is updated,
so if you need the scheduling changed you must either:
1. Change it on the annotation,
1. delete the job, and
1. then scan for jobs again by restarting the application or manually scheduling a rescan.

The annotation-scanning can be disabled by setting configuration property
`jobScheduler.job.jobService.scanForJobs.enabled: false`.
