package com.github.akhilrangaraj
import java.io.{BufferedWriter, File, FileWriter}

import com.amazonaws.services.batch.model._
import com.amazonaws.services.batch.{AWSBatch, AWSBatchClientBuilder}
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.TerminateInstancesRequest
import com.amazonaws.services.ecs.AmazonECSClientBuilder
import com.amazonaws.services.ecs.model.{DeregisterContainerInstanceRequest, DescribeContainerInstancesRequest, ListContainerInstancesRequest}
import org.rogach.scallop._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val kill = opt[Boolean]()
  val purge = opt[String]()
  val status = opt[String]()
  val replay = opt[Boolean]()
  val help = opt[Boolean]()
  val queueName = trailArg[String](required=true)
  verify()
}
object sovereign {
  private val cancelStatuses = Set(JobStatus.SUBMITTED, JobStatus.PENDING, JobStatus.RUNNABLE)
  private val terminateStatuses = Set(JobStatus.STARTING, JobStatus.RUNNING)

  val usage = s"""
    |Usage: sovereign [-k] [-p reason] [-s status] queueName
    |
    |-k Kill the instance with failures. Requires appropriate aws keys and permissions
    |-p Purge the queue with the specified message. Conflicts with -k or -s
    |-s Which status to look at. Must be one of ${JobStatus.values().mkString(",")}. Defaults to ${JobStatus.FAILED}
    |-h this message
    |-r replay failed jobs that failed due to container error
  """.stripMargin

  private def listJobs(queue: String, batch: AWSBatch, status: JobStatus = JobStatus.FAILED) : List[JobSummary] = {
    val jobBuffer = ListBuffer.empty[List[JobSummary]]
    var pageOption = Option.empty[String]
    var more = true
    while (more) {
      val listJobsRequest = new ListJobsRequest()
      listJobsRequest.setJobQueue(queue)
      if (pageOption.nonEmpty) listJobsRequest.setNextToken(pageOption.get)
      listJobsRequest.setJobStatus(status)
      val resp = batch.listJobs(listJobsRequest)
      pageOption = Option(resp.getNextToken)
      more = pageOption.nonEmpty
      jobBuffer += resp.getJobSummaryList.asScala.toList
    }
    jobBuffer.flatten.toList
  }

  /**
    * Replay any jobs that failed due to a docker error.
    * @param batch AWS Batch object
    * @param conf Config
    */
  def replay(batch: AWSBatch, conf: Conf) : Unit = {
    val file = new File(s"retry.log")
    val bw = new BufferedWriter(new FileWriter(file,true))
    try {
      val replayedJobs = scala.io.Source.fromFile(file).mkString.split("\n")


      val jobs = listJobs(conf.queueName.getOrElse(""), batch, JobStatus.FAILED)
      val jobsToReplay = ListBuffer.empty[JobDetail]
      for (jobGrouped <- jobs.grouped(100)) {
        val ids = for (job <- jobGrouped) yield job.getJobId
        val describeJobsRequest = new DescribeJobsRequest
        describeJobsRequest.setJobs(ids.asJava)
        val jobDescriptions = batch.describeJobs(describeJobsRequest)
        for (job <- jobDescriptions.getJobs.asScala) {
          var retry = false
          if (job.getAttempts().asScala.length > 1 && !job.getJobName.endsWith("-replay")) {
            for (attempt <- job.getAttempts().asScala) {
              if (attempt.getContainer() != null && attempt.getContainer.getReason != null) {
                if (attempt.getContainer.getReason.contains("DockerTimeoutError: Could not transition to created; timed out after waiting")) {
                  //retry any job that had an attempt fail for docker timeout error, even if it failed ultimately for another reason.
                  retry = true
                }
              }
            }
          }
          if (retry) jobsToReplay += job
        }
      }
      jobsToReplay.map(j => {
        if (!replayedJobs.contains(j.getJobName)) {
          val request = new SubmitJobRequest
          request.setJobQueue(conf.queueName.getOrElse(""))
          request.setJobDefinition(j.getJobDefinition)
          request.setJobName(s"${j.getJobName.substring(0, Math.min(90, j.getJobName.length))}-replay")
          val overrides = new ContainerOverrides
          overrides.setCommand(j.getContainer.getCommand)
          overrides.setEnvironment(j.getContainer.getEnvironment)
          overrides.setMemory(j.getContainer.getMemory)
          overrides.setVcpus(j.getContainer.getVcpus)
          request.setContainerOverrides(overrides)
          request.setRetryStrategy(j.getRetryStrategy)
          Console.println(s"Resubmitting ${j.getJobName}")
          batch.submitJob(request)
          bw.write(s"${j.getJobName}\n")
          bw.flush()
        }
      })
    } finally {
      bw.flush()
      bw.close()
    }
  }
  def kill(batch: AWSBatch, conf: Conf) : Unit =  {
    val ec2 = AmazonEC2ClientBuilder.defaultClient()
    val ecs = AmazonECSClientBuilder.defaultClient()
    val instanceClusterMapping = mutable.Map[String,String]()
    val instanceEc2IdMapping = mutable.Map[String,String]()
    for (cluster <- ecs.listClusters().getClusterArns.asScala.toList) {
      val clusterInstanceListRequest = new ListContainerInstancesRequest()
      clusterInstanceListRequest.setCluster(cluster)
      val instances = ecs.listContainerInstances(clusterInstanceListRequest)
      for (i <- instances.getContainerInstanceArns.asScala) instanceClusterMapping += (i -> cluster)
      if (instances.getContainerInstanceArns.asScala.length > 0) {
        val describeContainerInstanceRequest = new DescribeContainerInstancesRequest()
        describeContainerInstanceRequest.setCluster(cluster)
        describeContainerInstanceRequest.setContainerInstances(instances.getContainerInstanceArns)
        val dcrr = ecs.describeContainerInstances(describeContainerInstanceRequest)
        for (i <- dcrr.getContainerInstances.asScala) instanceEc2IdMapping += (i.getContainerInstanceArn -> i.getEc2InstanceId)
      }
    }
    val jobs = listJobs( conf.queueName.getOrElse(""), batch, JobStatus.fromValue(conf.status.getOrElse("FAILED")))
    val offendingInstances = ListBuffer.empty[String]
    for (jobGrouped <- jobs.grouped(100)) {

      val ids = for (job<-jobGrouped) yield job.getJobId
      val describeJobsRequest = new DescribeJobsRequest
      describeJobsRequest.setJobs(ids.asJava)
      val jobDescriptions = batch.describeJobs(describeJobsRequest)
      for (job<- jobDescriptions.getJobs.asScala) {
        if (job.getAttempts().asScala.length > 1) {
          for (attempt<- job.getAttempts().asScala) {
            if (attempt.getContainer() != null && attempt.getContainer.getReason != null) {
              if (attempt.getContainer.getReason.contains("DockerTimeoutError: Could not transition to created; timed out after waiting 4m0s")) {
                offendingInstances += attempt.getContainer.getContainerInstanceArn
              }
            }
          }
        }
      }
    }

    for (instance <- offendingInstances.distinct.toList) {
      if (instanceClusterMapping.contains(instance)) {
        val deregisterRequest = new DeregisterContainerInstanceRequest()
        deregisterRequest.setContainerInstance(instance)
        deregisterRequest.setCluster(instanceClusterMapping(instance))
        if (conf.kill.supplied)
          try {
            val terminateRequest = new TerminateInstancesRequest()
            terminateRequest.setInstanceIds(List(instanceEc2IdMapping(instance)).asJava)
            ec2.terminateInstances(terminateRequest)
          } catch {
            case e: Exception => {
              e.printStackTrace()
              Console.println("Couldnt deregister instance")
            }
          }
        Console.println(s"Kill $instance")
      }
    }
  }
  def purge(conf:Conf, batch: AWSBatch) = {
    for (status <- JobStatus.values()) if (cancelStatuses.contains(status)) {
      for (job <- listJobs(conf.queueName.getOrElse(""), batch,status)) {
        println(s"canceling ${job.getJobName}")
        val cancelRequest = new CancelJobRequest()
        cancelRequest.setJobId(job.getJobId)
        cancelRequest.setReason(conf.purge.getOrElse("Manual Cancel"))
        batch.cancelJob(cancelRequest)
      }
    } else if (terminateStatuses.contains(status)) {
      for (job <- listJobs(conf.queueName.getOrElse(""), batch,status)) {
        println(s"canceling ${job.getJobName}")
        val terminateRequest = new TerminateJobRequest()
        terminateRequest.setJobId(job.getJobId)
        terminateRequest.setReason(conf.purge.getOrElse("Manual Cancel"))
        batch.terminateJob(terminateRequest)
      }
    }
  }
  def main(args: Array[String]) = {
    if (args.length == 0) {
      println(usage)
      System.exit(1)
    }
    val conf = new Conf(args)
    if (conf.purge.supplied && conf.kill.supplied) {
      println(usage)
      System.exit(1)
    }
    val batch = AWSBatchClientBuilder.defaultClient()
    if (conf.help.isSupplied) {
      println(usage)
      System.exit(0)
    }
    if (conf.kill.isSupplied && conf.purge.isSupplied) {
      Console.println("Choose only 1 -k or -p")
      System.exit(1)
    }
    if (!conf.purge.isSupplied) kill(batch,conf) else purge(conf,batch)
    if (conf.replay.isSupplied) replay(batch,conf)
  }
}
