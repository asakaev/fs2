package fs2

import java.util.concurrent.atomic.AtomicLong

import org.scalacheck.Gen
import cats.effect.IO
import cats.implicits.{catsSyntaxEither ⇒ _, _}

import scala.concurrent.duration._
import TestUtil._
import fs2.Stream._

class PipeSpec extends Fs2Spec {

  "Pipe" - {

    "mapAsyncUnordered" in forAll { s: PureStream[Int] =>
      val f = (_: Int) + 1
      val r = s.get.covary[IO].mapAsyncUnordered(16)(i => IO(f(i)))
      runLog(r) should contain theSameElementsAs runLog(s.get).map(f)
    }

    "exists" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      runLog(s.get.exists(f)) shouldBe Vector(runLog(s.get).exists(f))
    }

    "filter" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val predicate = (i: Int) => i % n.get == 0
      runLog(s.get.filter(predicate)) shouldBe runLog(s.get).filter(predicate)
    }

    "filter (2)" in forAll { (s: PureStream[Double]) =>
      val predicate = (i: Double) => i - i.floor < 0.5
      val s2 = s.get.mapChunks(c => Chunk.doubles(c.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "filter (3)" in forAll { (s: PureStream[Byte]) =>
      val predicate = (b: Byte) => b < 0
      val s2 = s.get.mapChunks(c => Chunk.bytes(c.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "filter (4)" in forAll { (s: PureStream[Boolean]) =>
      val predicate = (b: Boolean) => !b
      val s2 = s.get.mapChunks(c => Chunk.booleans(c.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "find" in forAll { (s: PureStream[Int], i: Int) =>
      val predicate = (item: Int) => item < i
      runLog(s.get.find(predicate)) shouldBe runLog(s.get)
        .find(predicate)
        .toVector
    }

    "fold" in forAll { (s: PureStream[Int], n: Int) =>
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.fold(n)(f)) shouldBe Vector(runLog(s.get).foldLeft(n)(f))
    }

    "fold (2)" in forAll { (s: PureStream[Int], n: String) =>
      val f = (a: String, b: Int) => a + b
      runLog(s.get.fold(n)(f)) shouldBe Vector(runLog(s.get).foldLeft(n)(f))
    }

    "foldMonoid" in forAll { (s: PureStream[Int]) =>
      s.get.foldMonoid.toVector shouldBe Vector(runLog(s.get).combineAll)
    }

    "foldMonoid (2)" in forAll { (s: PureStream[Double]) =>
      s.get.foldMonoid.toVector shouldBe Vector(runLog(s.get).combineAll)
    }

    "fold1" in forAll { (s: PureStream[Int]) =>
      val v = runLog(s.get)
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.fold1(f)) shouldBe v.headOption.fold(Vector.empty[Int])(h =>
        Vector(v.drop(1).foldLeft(h)(f)))
    }

    "forall" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      runLog(s.get.forall(f)) shouldBe Vector(runLog(s.get).forall(f))
    }

    "groupAdjacentBy" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get
      val s1 = s.get.groupAdjacentBy(f)
      val s2 = s.get.map(f).changes
      runLog(s1.map(_._2)).flatMap(_.toVector) shouldBe runLog(s.get)
      runLog(s1.map(_._1)) shouldBe runLog(s2)
      runLog(s1.map { case (k, vs) => vs.toVector.forall(f(_) == k) }) shouldBe runLog(
        s2.map(_ => true))
    }

    "head" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.head) shouldBe runLog(s.get).take(1)
    }

    "intersperse" in forAll { (s: PureStream[Int], n: Int) =>
      runLog(s.get.intersperse(n)) shouldBe runLog(s.get)
        .flatMap(i => Vector(i, n))
        .dropRight(1)
    }

    "mapChunks" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.mapChunks(identity).chunks) shouldBe runLog(s.get.chunks)
    }

    "performance of multi-stage pipeline" in {
      val v = Vector.fill(1000)(Vector.empty[Int])
      val v2 = Vector.fill(1000)(Vector(0))
      val s = (v.map(Stream.emits(_)): Vector[Stream[Pure, Int]]).reduce(_ ++ _)
      val s2 =
        (v2.map(Stream.emits(_)): Vector[Stream[Pure, Int]]).reduce(_ ++ _)
      val id = (_: Stream[Pure, Int]).mapChunks(identity)
      runLog(s.through(id).through(id).through(id).through(id).through(id)) shouldBe Vector()
      runLog(s2.through(id).through(id).through(id).through(id).through(id)) shouldBe Vector
        .fill(1000)(0)
    }

    "last" in forAll { (s: PureStream[Int]) =>
      val _ = s.get.last
      runLog(s.get.last) shouldBe Vector(runLog(s.get).lastOption)
    }

    "lastOr" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val default = n.get
      runLog(s.get.lastOr(default)) shouldBe Vector(runLog(s.get).lastOption.getOrElse(default))
    }

    "mapAccumulate" in forAll { (s: PureStream[Int], n0: Int, n1: SmallPositive) =>
      val f = (_: Int) % n1.get == 0
      val r = s.get.mapAccumulate(n0)((s, i) => (s + i, f(i)))

      runLog(r.map(_._1)) shouldBe runLog(s.get).scanLeft(n0)(_ + _).tail
      runLog(r.map(_._2)) shouldBe runLog(s.get).map(f)
    }

    "prefetch" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.covary[IO].prefetch) shouldBe runLog(s.get)
    }

    "prefetch (timing)" in {
      // should finish in about 3-4 seconds
      val s = Stream(1, 2, 3)
        .evalMap(i => IO { Thread.sleep(1000); i })
        .prefetch
        .flatMap { i =>
          Stream.eval(IO { Thread.sleep(1000); i })
        }
      val start = System.currentTimeMillis
      runLog(s)
      val stop = System.currentTimeMillis
      println(
        "prefetch (timing) took " + (stop - start) + " milliseconds, should be under 6000 milliseconds")
      assert((stop - start) < 6000)
    }

    "sliding" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      s.get.sliding(n.get).toList.map(_.toList) shouldBe s.get.toList
        .sliding(n.get)
        .map(_.toList)
        .toList
    }

    "split" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val s2 = s.get
        .map(x => if (x == Int.MinValue) x + 1 else x)
        .map(_.abs)
        .filter(_ != 0)
      withClue(s"n = $n, s = ${s.get.toList}, s2 = " + s2.toList) {
        runLog {
          s2.chunkLimit(n.get)
            .intersperse(Chunk.singleton(0))
            .flatMap(Stream.chunk)
            .split(_ == 0)
            .map(_.toVector)
            .filter(_.nonEmpty)
        } shouldBe
          s2.chunkLimit(n.get).filter(_.nonEmpty).map(_.toVector).toVector
      }
    }

    "split (2)" in {
      Stream(1, 2, 0, 0, 3, 0, 4).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1,
                                                                                                2),
                                                                                         Vector(),
                                                                                         Vector(3),
                                                                                         Vector(4))
      Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1, 2),
                                                                                      Vector(),
                                                                                      Vector(3))
      Stream(1, 2, 0, 0, 3, 0, 0).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1,
                                                                                                2),
                                                                                         Vector(),
                                                                                         Vector(3),
                                                                                         Vector())
    }

    "take" in forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
      val n = if (negate) -n0.get else n0.get
      runLog(s.get.take(n)) shouldBe runLog(s.get).take(n)
    }

    "takeRight" in forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
      val n = if (negate) -n0.get else n0.get
      runLog(s.get.takeRight(n)) shouldBe runLog(s.get).takeRight(n)
    }

    "takeWhile" in forAll { (s: PureStream[Int], n: SmallNonnegative) =>
      val set = runLog(s.get).take(n.get).toSet
      runLog(s.get.takeWhile(set)) shouldBe runLog(s.get).takeWhile(set)
    }

    "takeThrough" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      val vec = runLog(s.get)
      val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
      withClue(s.get.toList)(runLog(s.get.takeThrough(f)) shouldBe result)
    }

    "scan (simple ex)" in {
      val s = PureStream("simple", Stream(1).map(x => x)) // note, without the .map, test passes
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.scan(0)(f)) shouldBe runLog(s.get).scanLeft(0)(f)
    }

    "scan (temporal)" in {
      val never = Stream.eval(IO.async[Int](_ => ()))
      val s = Stream(1)
      val f = (a: Int, b: Int) => a + b
      val result = s.toVector.scanLeft(0)(f)
      runLog((s ++ never).scan(0)(f).take(result.size))(1.second) shouldBe result
    }

    "scan" in forAll { (s: PureStream[Int], n: Int) =>
      val f = (a: Int, b: Int) => a + b
      try runLog(s.get.scan(n)(f)) shouldBe runLog(s.get).scanLeft(n)(f)
      catch {
        case e: Throwable =>
          println(s.get.toList)
          throw e
      }
    }

    "scan (2)" in forAll { (s: PureStream[Int], n: String) =>
      val f = (a: String, b: Int) => a + b
      runLog(s.get.scan(n)(f)) shouldBe runLog(s.get).scanLeft(n)(f)
    }

    "scan1" in forAll { (s: PureStream[Int]) =>
      val v = runLog(s.get)
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.scan1(f)) shouldBe v.headOption.fold(Vector.empty[Int])(h =>
        v.drop(1).scanLeft(h)(f))
    }

    "tail" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.tail) shouldBe runLog(s.get).drop(1)
    }

    "take.chunks" in {
      val s = Stream(1, 2) ++ Stream(3, 4)
      runLog(s.take(3).chunks.map(_.toVector)) shouldBe Vector(Vector(1, 2), Vector(3))
    }

    "unNone" in forAll { (s: PureStream[Option[Int]]) =>
      runLog(s.get.unNone.chunks) shouldBe runLog(s.get.filter(_.isDefined).map(_.get).chunks)
    }

    "zipWithIndex" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithIndex) shouldBe runLog(s.get).zipWithIndex
    }

    "zipWithNext" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithNext) shouldBe {
        val xs = runLog(s.get)
        xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
      }
    }

    "zipWithNext (2)" in {
      runLog(Stream().zipWithNext) shouldBe Vector()
      runLog(Stream(0).zipWithNext) shouldBe Vector((0, None))
      runLog(Stream(0, 1, 2).zipWithNext) shouldBe Vector((0, Some(1)), (1, Some(2)), (2, None))
    }

    "zipWithPrevious" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithPrevious) shouldBe {
        val xs = runLog(s.get)
        (None +: xs.map(Some(_))).zip(xs)
      }
    }

    "zipWithPrevious (2)" in {
      runLog(Stream().zipWithPrevious) shouldBe Vector()
      runLog(Stream(0).zipWithPrevious) shouldBe Vector((None, 0))
      runLog(Stream(0, 1, 2).zipWithPrevious) shouldBe Vector((None, 0), (Some(0), 1), (Some(1), 2))
    }

    "zipWithPreviousAndNext" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithPreviousAndNext) shouldBe {
        val xs = runLog(s.get)
        val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
        val zipWithPreviousAndNext = zipWithPrevious
          .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
          .map { case ((prev, that), next) => (prev, that, next) }

        zipWithPreviousAndNext
      }
    }

    "zipWithPreviousAndNext (2)" in {
      runLog(Stream().zipWithPreviousAndNext) shouldBe Vector()
      runLog(Stream(0).zipWithPreviousAndNext) shouldBe Vector((None, 0, None))
      runLog(Stream(0, 1, 2).zipWithPreviousAndNext) shouldBe Vector((None, 0, Some(1)),
                                                                     (Some(0), 1, Some(2)),
                                                                     (Some(1), 2, None))
    }

    "zipWithScan" in {
      runLog(
        Stream("uno", "dos", "tres", "cuatro")
          .zipWithScan(0)(_ + _.length)) shouldBe Vector("uno" -> 0,
                                                         "dos" -> 3,
                                                         "tres" -> 6,
                                                         "cuatro" -> 10)
      runLog(Stream().zipWithScan(())((acc, i) => ???)) shouldBe Vector()
    }

    "zipWithScan1" in {
      runLog(
        Stream("uno", "dos", "tres", "cuatro")
          .zipWithScan1(0)(_ + _.length)) shouldBe Vector("uno" -> 3,
                                                          "dos" -> 6,
                                                          "tres" -> 10,
                                                          "cuatro" -> 16)
      runLog(Stream().zipWithScan1(())((acc, i) => ???)) shouldBe Vector()
    }

    "observe/observeAsync" - {
      "basic functionality" in {
        forAll { (s: PureStream[Int]) =>
          val sum = new AtomicLong(0)
          val out = runLog {
            s.get.covary[IO].observe {
              _.evalMap(i => IO { sum.addAndGet(i.toLong); () })
            }
          }
          out.map(_.toLong).sum shouldBe sum.get
          sum.set(0)
          val out2 = runLog {
            s.get.covary[IO].observeAsync(maxQueued = 10) {
              _.evalMap(i => IO { sum.addAndGet(i.toLong); () })
            }
          }
          out2.map(_.toLong).sum shouldBe sum.get
        }
      }

      "observe is not eager (1)" in {
        //Do not pull another element before we emit the currently processed one
        (Stream.eval(IO(1)) ++ Stream.eval(IO.raiseError(new Throwable("Boom"))))
          .observe(_.evalMap(_ => IO(Thread.sleep(100)))) //Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(1)
          .compile
          .toVector
          .unsafeRunSync shouldBe Vector(1)
      }

      "observe is not eager (2)" in {
        //Do not pull another element before the downstream asks for another
        (Stream.eval(IO(1)) ++ Stream.eval(IO.raiseError(new Throwable("Boom"))))
          .observe(_.drain)
          .flatMap(_ => Stream.eval(IO(Thread.sleep(100))) >> Stream(1, 2)) //Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(2)
          .compile
          .toVector
          .unsafeRunSync shouldBe Vector(1, 2)
      }

    }
    "handle errors from observing sink" in {
      forAll { (s: PureStream[Int]) =>
        val r1 = runLog {
          s.get
            .covary[IO]
            .observe { _ =>
              Stream.raiseError[IO](new Err)
            }
            .attempt
        }
        r1 should have size (1)
        r1.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
        val r2 = runLog {
          s.get
            .covary[IO]
            .observeAsync(2) { _ =>
              Stream.raiseError[IO](new Err)
            }
            .attempt
        }
        r2 should have size (1)
        r2.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
      }
    }

    "propagate error from source" in {
      forAll { (f: Failure) =>
        val r1 = runLog {
          f.get
            .covary[IO]
            .observe(_.drain)
            .attempt
        }
        r1 should have size (1)
        r1.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
        val r2 = runLog {
          f.get
            .covary[IO]
            .observeAsync(2)(_.drain)
            .attempt
        }
        r2 should have size (1)
        r2.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
      }
    }

    "handle finite observing sink" in {
      forAll { (s: PureStream[Int]) =>
        runLog {
          s.get.covary[IO].observe { _ =>
            Stream.empty
          }
        } shouldBe Vector.empty
        runLog {
          s.get.covary[IO].observe { _.take(2).drain }
        }
        runLog {
          s.get.covary[IO].observeAsync(2) { _ =>
            Stream.empty
          }
        } shouldBe Vector.empty
      }
    }
    "handle multiple consecutive observations" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        runLog {
          val sink: Pipe[IO, Int, Unit] = _.evalMap(i => IO(()))
          val src: Stream[IO, Int] = s.get.covary[IO]
          src.observe(sink).observe(sink)
        } shouldBe s.get.toVector
      }
    }
    "no hangs on failures" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        swallow {
          runLog {
            val sink: Pipe[IO, Int, Unit] =
              in => spuriousFail(in.evalMap(i => IO(i)), f).map(_ => ())
            val src: Stream[IO, Int] = spuriousFail(s.get.covary[IO], f)
            src.observe(sink).observe(sink)
          }
        }
      }
    }

  }

}