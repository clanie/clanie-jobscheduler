# Job Scheduler

Configuration properties:

```
jobScheduler:
  enabled: true
  maxParallelJobs: 2
  pollInterval: PT1M
  exitWhenIdle: true
```


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
