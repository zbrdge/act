package com.act.analysis.proteome.tool_manager.jobs

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}

class ScalaJob(command: Map[String, Any] => Unit, arguments: Map[String, Any]) extends Job {
  def asyncJob() {
    // Run the call in the future
    val future: Future[Any] = Future {
      blocking {
        this.command(arguments)
      }
    }

    // Setup Job's success/failure
    future.onComplete({
      case Success(x) => markAsSuccess()
      case Failure(x) => markAsFailure(); JobManager.logError(s"Cause of failure was ${x.getMessage}")
    })
  }
}
