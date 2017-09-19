import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder

object CollectionHelp {

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

}
