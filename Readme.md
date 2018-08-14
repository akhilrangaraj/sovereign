https://www.youtube.com/watch?v=R_NAoNd4YyY


because ECS and Batch are not so great

simple tool to kill ECS instances that are misbehaving, purge a batch job queue, and replay failed jobs.


```
    Usage: sovereign [-k] [-p reason] [-s status] queueName
   
   -k Kill the instance with failures. Requires appropriate aws keys and permissions
   -a ask to retry all failed jobs
   -p Purge the queue with the specified message. Conflicts with -k or -s
   -s Which status to look at. Must be one of ${JobStatus.values().mkString(",")}. Defaults to ${JobStatus.FAILED}
   -h this message
   -r replay failed jobs that failed due to container error
 ```