import akka.event.LoggingAdapter

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder
import scala.concurrent.{ExecutionContext, Future}

object Utils {

  implicit class EitherCompanionOps(val eitherType: Either.type) extends AnyVal {

    def sequence[A,B, M[X] <: TraversableOnce[X]](eithers:  M[Either[A,B]])(
      implicit
      cbfLeft:  CanBuildFrom[M[Either[A,B]], A, M[A]],
      cbfRight: CanBuildFrom[M[Either[A,B]], B, M[B]]):
    Either[M[A], M[B]] = {

      val successesBuilder: Builder[B, M[B]] = cbfRight.apply(eithers)
      val zero: Either[Builder[A, M[A]], Builder[B, M[B]]] = Right(successesBuilder)

      eithers.foldLeft(zero){
        (_,_) match {
          case (Left(errorsBuilder), Right(_)) =>  Left(errorsBuilder)
          case( (Left(errorsBuilder), Left(anError))) => Left(errorsBuilder += anError)
          case( (Right(_), Left(error) )   ) => Left(  cbfLeft.apply(eithers) += error   )
          case( (Right(successes), Right(aSuccess))) => Right(successes += aSuccess)
        }
      }.fold(
      {errorsBuilder => Left(errorsBuilder.result())},
      {successesBuilder => Right(successesBuilder.result())}

      )

    }
  }


  //short circuit upon the first failure
  def runSequentially[K,V](args: List[K], f: K => Future[V], accum: List[V] = List()  )(implicit ec: ExecutionContext): Future[List[V]] = {

    args match {
      case Nil => Future.successful(accum.reverse)
      case k :: ks =>
        f(k).flatMap{v =>
          runSequentially(ks, f, v :: accum)
        }
    }

  }

  /**
   * Run in parallel, and if one or more Future fails, others may still succeed. The result of running an f against
   * a K will be associated with that K, and a Vector of the results is returned. If there was one or more failures,
   * result will be a Vector of K -> Throwable pairs. If all succeeded, the result is a Vector of K -> V
   */
  def runManyGeneric[K, V](args: Vector[K], f: K => Future[V])(implicit ec: ExecutionContext, logger: LoggingAdapter): Future[Either[ Vector[(K,Throwable)], Vector[(K,V)]  ]] = {
    Future.sequence(args.map(arg =>
      f(arg).map{v =>
        Right(arg ->  v)
      }.recover{
        case ex =>
          logger.warning(s"failure. is is ${arg} and ex is $ex")
          Left(arg -> ex)
      }

    )).map{vec: Vector[Either[(K, Throwable), (K,V)]] => Either.sequence(vec)   }
  }


}
