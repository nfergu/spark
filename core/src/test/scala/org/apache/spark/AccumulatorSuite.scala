/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.collection.mutable

import org.scalatest.FunSuite
import org.scalatest.Matchers

import org.apache.spark.SparkContext._

class AccumulatorSuite extends FunSuite with Matchers with LocalSparkContext {


  implicit def setAccum[A] = new AccumulableParam[mutable.Set[A], A] {
    def addInPlace(t1: mutable.Set[A], t2: mutable.Set[A]) : mutable.Set[A] = {
      t1 ++= t2
      t1
    }
    def addAccumulator(t1: mutable.Set[A], t2: A) : mutable.Set[A] = {
      t1 += t2
      t1
    }
    def zero(t: mutable.Set[A]) : mutable.Set[A] = {
      new mutable.HashSet[A]()
    }
  }

  test ("basic accumulation"){
    sc = new SparkContext("local", "test")
    val acc : Accumulator[Int] = sc.accumulator(0)

    val d = sc.parallelize(1 to 20)
    d.foreach{x => acc += x}
    acc.value should be (210)


    val longAcc = sc.accumulator(0l)
    val maxInt = Integer.MAX_VALUE.toLong
    d.foreach{x => longAcc += maxInt + x}
    longAcc.value should be (210l + maxInt * 20)
  }

  test ("value not assignable from tasks") {
    sc = new SparkContext("local", "test")
    val acc : Accumulator[Int] = sc.accumulator(0)

    val d = sc.parallelize(1 to 20)
    an [Exception] should be thrownBy {d.foreach{x => acc.value = x}}
  }

  test ("add value to collection accumulators") {
    val maxI = 1000
    for (nThreads <- List(1, 10)) { // test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc: Accumulable[mutable.Set[Any], Any] = sc.accumulable(new mutable.HashSet[Any]())
      val d = sc.parallelize(1 to maxI)
      d.foreach {
        x => acc += x
      }
      val v = acc.value.asInstanceOf[mutable.Set[Int]]
      for (i <- 1 to maxI) {
        v should contain(i)
      }
      resetSparkContext()
    }
  }

  test ("value not readable in tasks") {
    val maxI = 1000
    for (nThreads <- List(1, 10)) { // test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc: Accumulable[mutable.Set[Any], Any] = sc.accumulable(new mutable.HashSet[Any]())
      val d = sc.parallelize(1 to maxI)
      an [SparkException] should be thrownBy {
        d.foreach {
          x => acc.value += x
        }
      }
      resetSparkContext()
    }
  }

  test ("collection accumulators") {
    val maxI = 1000
    for (nThreads <- List(1, 10)) {
      // test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val setAcc = sc.accumulableCollection(mutable.HashSet[Int]())
      val bufferAcc = sc.accumulableCollection(mutable.ArrayBuffer[Int]())
      val mapAcc = sc.accumulableCollection(mutable.HashMap[Int,String]())
      val d = sc.parallelize((1 to maxI) ++ (1 to maxI))
      d.foreach {
        x => {setAcc += x; bufferAcc += x; mapAcc += (x -> x.toString)}
      }

      // Note that this is typed correctly -- no casts necessary
      setAcc.value.size should be (maxI)
      bufferAcc.value.size should be (2 * maxI)
      mapAcc.value.size should be (maxI)
      for (i <- 1 to maxI) {
        setAcc.value should contain(i)
        bufferAcc.value should contain(i)
        mapAcc.value should contain (i -> i.toString)
      }
      resetSparkContext()
    }
  }

  test ("localValue readable in tasks") {
    val maxI = 1000
    for (nThreads <- List(1, 10)) { // test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc: Accumulable[mutable.Set[Any], Any] = sc.accumulable(new mutable.HashSet[Any]())
      val groupedInts = (1 to (maxI/20)).map {x => (20 * (x - 1) to 20 * x).toSet}
      val d = sc.parallelize(groupedInts)
      d.foreach {
        x => acc.localValue ++= x
      }
      acc.value should be ( (0 to maxI).toSet)
      resetSparkContext()
    }
  }

  test ("basic accumulation works with named accumulables"){
    // Just a basic test to ensure that we haven't broken accumulation by the addition
    // of the AccumulableRegistry, and associated functionality
    sc = new SparkContext("local", "test")
    val acc : Accumulator[Int] = sc.accumulator(0, "myaccum")
    val d = sc.parallelize(1 to 20)
    d.foreach{x => acc += x}
    acc.value should be (210)
  }

  test ("named accumulables available in the registry") {
    for (nThreads <- List(1, 10)) { // test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc : Accumulator[Int] = sc.accumulator(0, "myaccum")
      val acc2 : Accumulator[Int] = sc.accumulator(0, "myaccum2")
      val d = sc.parallelize(1 to 20, nThreads)
      d.foreach{x => {
        AccumulableRegistry.get("myaccum").get.asInstanceOf[Accumulator[Int]] += x
        AccumulableRegistry.get("myaccum2").get.asInstanceOf[Accumulator[Int]] += (x*2)
      }}
      acc.value should be (210)
      acc2.value should be (420)
      resetSparkContext()
    }
  }

  test ("accumulables can be passed explicitly as well as obtained from the registry") {
    for (nThreads <- List(1, 10)) { // test single & multi-threaded
      sc = new SparkContext("local[" + nThreads + "]", "test")
      val acc : Accumulator[Int] = sc.accumulator(0, "myaccum")
      val d = sc.parallelize(1 to 20, nThreads)
      d.foreach{x => {
        AccumulableRegistry.get("myaccum").get.asInstanceOf[Accumulator[Int]] += x
        acc += (x*2)
      }}
      acc.value should be (630)
      resetSparkContext()
    }
  }

  // Ensures that only named accumulables that were created using the SparkContext that is
  // running the job are used. Accumulables "left over" from other SparkContexts should not
  // be broadcast.
  test ("named accumulables for the correct SparkContext are used") {
    for (i <- 0 to 1) {
      sc = new SparkContext("local", "test")
      val acc : Accumulator[Int] = sc.accumulator(0, "myaccum" + i)
      val d = sc.parallelize(1 to 20)
      d.foreach{x => {
        AccumulableRegistry.get("myaccum" + i).get.asInstanceOf[Accumulator[Int]] += x
        if (AccumulableRegistry.get("myaccum" + (i - 1)).isDefined) {
          throw new IllegalStateException("Did not expect to find the accumulator [" +
              ("myaccum" + (i - 1)) + "]")
        }
      }}
      acc.value should be (210)
      resetSparkContext()
    }
  }

  test ("most recent named accumulable is returned from the registry") {
    sc = new SparkContext("local", "test")
    val acc : Accumulator[Int] = sc.accumulator(0, "myaccum")
    val acc1 : Accumulator[Int] = sc.accumulator(0, "myaccum")
    val acc2 : Accumulator[Int] = sc.accumulator(0, "myaccum")
    val d = sc.parallelize(1 to 20)
    d.foreach{x => {
      AccumulableRegistry.get("myaccum").get.asInstanceOf[Accumulator[Int]] += x
    }}
    // If there are multiple named accumulables with the same name, the most recently-created
    // one should be used when retrieving from the AccumulableRegistry
    acc.value should be (0)
    acc1.value should be (0)
    acc2.value should be (210)
    resetSparkContext()
  }

}
