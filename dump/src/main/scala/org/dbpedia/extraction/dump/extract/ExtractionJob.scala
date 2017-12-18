package org.dbpedia.extraction.dump.extract

import java.io.File
import java.net.URL

import org.apache.spark.SparkContext
import org.dbpedia.extraction.config.Config
import org.dbpedia.extraction.config.provenance.Dataset
import org.dbpedia.extraction.destinations.Destination
import org.dbpedia.extraction.destinations.formatters.Formatter
import org.dbpedia.extraction.mappings._
import org.dbpedia.extraction.ontology.Ontology
import org.dbpedia.extraction.ontology.io.OntologyReader
import org.dbpedia.extraction.sources.{Source, WikiSource, XMLSource}
import org.dbpedia.extraction.util.{RecordSeverity, _}
import org.dbpedia.extraction.wikiparser.{Namespace, WikiPage}


/**
 * Executes a extraction.
 *
 * @param extractors The Extractors
 * @param context The Extraction Context
 * @param namespaces Only extract pages in these namespaces
 * @param lang the language of this extraction.
 */
class ExtractionJob(
   extractors: Seq[Class[_ <: Extractor[_]]],
   context: DumpExtractionContext,
   articleSource : Source,
   namespaces: Set[Namespace],
   destination: Destination,
   lang: Language,
   retryFailedPages: Boolean,
   extractionRecorder: ExtractionRecorder[WikiPage])
{

  def run(sparkContext: SparkContext, config : Config): Unit =
  {

    // Broadcasts
    val bc_namespaces = sparkContext.broadcast(namespaces)

    val bc_ontology = sparkContext.broadcast(context.ontology)
    val bc_language = sparkContext.broadcast(context.language)
    val bc_redirects= sparkContext.broadcast(context.redirects)

    val bc_mappingPageSource = sparkContext.broadcast(context.mappingPageSource)

    def worker_context = new DumpExtractionContext {
      def ontology: Ontology = bc_ontology.value

      def language: Language = bc_language.value

      def redirects: Redirects = bc_redirects.value

      //val mappings = MappingsLoader.load(this)

      def mappingPageSource: Traversable[WikiPage] = bc_mappingPageSource.value

    }

    // Create Extractors
    val extractor = CompositeParseExtractor.load(extractors, worker_context)

    val bc_extractor = sparkContext.broadcast(extractor)

    // Source -> RDD[WikiPage]
    val source = sparkContext.parallelize(articleSource.toSeq)

    extractionRecorder.initialize(lang, "Main Extraction", extractor.datasets.toSeq)
    extractor.initializeExtractor()
    destination.open()

    try {
      // Run Extraction on Spark RDD
      source.map(page => {
        if (bc_namespaces.value.exists(_.equals(page.title.namespace)))
          Some(bc_extractor.value.extract(page, page.uri))
        else
          None
      })
      // Collect Quads
        .collect()
      // Write Quads to Destination
        .foreach {
          case Some(quads) =>
            quads.foreach(q => println("s: " + q.subject + "\np: " + q.predicate + "\no: " + q.value + "\n\n"))
            destination.write(quads)
          case None =>
        }

      extractionRecorder.printLabeledLine("finished extraction after {page} pages with {mspp} per page", RecordSeverity.Info, lang)

      // Retry Extraction of failed pages
      if(retryFailedPages) {
        val failedPages = extractionRecorder.listFailedPages(lang).keys.map(_._2).toSeq

        // Failed Pages -> SparkRDD[WikiPage]
        val fails = sparkContext.parallelize(failedPages)
        extractionRecorder.printLabeledLine("retrying " + failedPages.length + " failed pages", RecordSeverity.Warning, lang)
        extractionRecorder.resetFailedPages(lang)

        // Rerun Extraction on SparkRDD
        fails.map(page => {
          page.toggleRetry()
          extractor.extract(page, page.uri)
        })
        // Collect Quads
          .collect()
        // Write Quads to Destination
          .foreach(destination.write)
        extractionRecorder.printLabeledLine("all failed pages were re-executed.", RecordSeverity.Info, lang)
      }
    }
    catch {
      case ex : Throwable =>
        if(extractionRecorder.monitor != null) extractionRecorder.monitor.reportCrash(extractionRecorder, ex)
        ex.printStackTrace()
    } finally {
      destination.close()
      extractor.finalizeExtractor()
      extractionRecorder.finalize()
    }
  }

}

