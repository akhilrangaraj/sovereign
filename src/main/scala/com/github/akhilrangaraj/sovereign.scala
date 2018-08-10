package com.github.akhilrangaraj
import com.amazonaws.services.batch.model._
import com.amazonaws.services.batch.{AWSBatch, AWSBatchClientBuilder}
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ecs.AmazonECSClientBuilder
import com.amazonaws.services.ecs.model.{DeregisterContainerInstanceRequest}
import org.rogach.scallop._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val kill = opt[Boolean]()
  val purge = opt[Boolean]()
  val status = opt[String]()
  val queueName = trailArg[String](required=true)
  verify()
}
object sovereign {

  val usage = s"""
    |Usage: sovereign [-k] [-p] [-s status] queueName
    |
    |-k Kill the instance with failures. Requires appropriate aws keys and permissions
    |-p Purge the queue. Conflicts with -k or -s
    |-s Which status to look at. Must be one of ${JobStatus.values().mkString(",")}. Defaults to ${JobStatus.FAILED}
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
  def kill(batch: AWSBatch, conf: Conf) : Unit =  {
    val ec2 = AmazonEC2ClientBuilder.defaultClient()
    val ecs = AmazonECSClientBuilder.defaultClient()
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
      val deregisterRequest = new DeregisterContainerInstanceRequest()
      deregisterRequest.setContainerInstance(instance)
      if (conf.kill.supplied)
        ecs.deregisterContainerInstance(deregisterRequest)
      else {
        Console.println(s"Kill $instance")
      }
    }
  }
  def purge(conf:Conf, batch: AWSBatch) = {
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
    if (conf.kill.isSupplied && conf.purge.isSupplied) {
      Console.println("Choose only 1 -k or -p")
      System.exit(1)
    }
    if (!conf.purge.isSupplied) kill(batch,conf) else purge(conf,batch)
  }
}