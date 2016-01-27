package com.stripe.brushfire
package features

import scala.util.Try

import com.twitter.algebird.{ Aggregator, Semigroup, MapMonoid }

object CsvRowFeatureMapping {
  def isDouble(s: String): Boolean = Try(s.toDouble).isSuccess

  case class NominalCount(uniques: Option[Set[String]]) {
    def ++ (that: NominalCount): NominalCount =
      NominalCount(for {
        a <- this.uniques
        b <- that.uniques
        c = a ++ b
        if (c.size < 20)
      } yield c)
  }

  case class Counts(doubles: Long, nominals: NominalCount, count: Long) {
    def ++(that: Counts): Counts =
      Counts(this.doubles + that.doubles, this.nominals ++ that.nominals, this.count + that.count)

    def featureType: FeatureType = {
      val isNumeric = doubles == count
      val isSmall = nominals.uniques.isDefined
      (isNumeric, isSmall) match {
        case (true, true) => FeatureType.Ordinal
        case (true, false) => FeatureType.Continuous
        case (false, true) => FeatureType.Nominal
        case (false, false) => FeatureType.Sparse
      }
    }
  }

  object Counts {
    def apply(value: String): Counts =
      Counts(if (isDouble(value)) 1 else 0, NominalCount(Some(Set(value))), 1)

    implicit val CountsSemigroup: Semigroup[Counts] =
      Semigroup.from[Counts](_ ++ _)

    val aggregator: Aggregator[String, Counts, FeatureType] =
      new Aggregator[String, Counts, FeatureType] {
        def prepare(input: String): Counts = Counts(input)
        def semigroup: Semigroup[Counts] = CountsSemigroup
        def present(reduction: Counts): FeatureType = reduction.featureType
      }
  }

  case class IndexedSeqAggregator[-A, B, +C](agg: Aggregator[A, B, C])
  extends Aggregator[IndexedSeq[A], IndexedSeq[B], IndexedSeq[C]] {
    def prepare(input: IndexedSeq[A]): IndexedSeq[B] =
      input.map { a => agg.prepare(a) }
    val semigroup: Semigroup[IndexedSeq[B]] =
      Semigroup.indexedSeqSemigroup(agg.semigroup)
    def present(reduction: IndexedSeq[B]): IndexedSeq[C] =
      reduction.map { b => agg.present(b) }
  }

  def apply(header: CsvRow, featureTypes: IndexedSeq[FeatureType]): FeatureMapping[CsvRow] = {
    val mapping: Map[String, (FeatureType, CsvRow => FeatureValue)] = {
      def extractor(index: Int, featureType: FeatureType): CsvRow => FeatureValue = {
        def mk(f: String => FeatureValue): CsvRow => FeatureValue = row => f(row(index))
        featureType match {
          case FeatureType.Ordinal => mk(x => Dispatched.ordinal(x.toDouble))
          case FeatureType.Nominal => mk(Dispatched.nominal)
          case FeatureType.Continuous => mk(x => Dispatched.continuous(x.toDouble))
          case FeatureType.Sparse => mk(Dispatched.sparse)
        }
      }

      (header zip featureTypes).zipWithIndex.map { case ((key, ft), i) =>
        key -> (ft, extractor(i, ft))
      } (collection.breakOut)
    }
    FeatureMapping(mapping)
  }

  def aggregator(header: CsvRow): Aggregator[CsvRow, IndexedSeq[Counts], FeatureMapping[CsvRow]] =
    IndexedSeqAggregator(Counts.aggregator)
      .andThenPresent(apply(header, _))

  def apply(header: CsvRow, rows: TraversableOnce[CsvRow]): FeatureMapping[CsvRow] =
    aggregator(header).apply(rows)
}
