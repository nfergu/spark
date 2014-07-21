package org.apache.spark

import scala.util.DynamicVariable

object SparkDynamic {

  private val dynamicVariable = new DynamicVariable[Any]()

  /**
   * Gets the value of the "dynamic variable" that has been set in the [[SparkContext]]
   */
  def getValue: Option[Any] = {
    Option(dynamicVariable.value)
  }

  private[spark] def withValue[S](threadValue: Option[Any])(thunk: => S): S = {
    dynamicVariable.withValue(threadValue.orNull)(thunk)
  }

}
