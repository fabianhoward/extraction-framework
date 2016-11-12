package org.dbpedia.extraction.mappings

import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.sources.FileSource
import org.dbpedia.extraction.util.{Config, ConfigUtils, Language}
import java.io.{FilenameFilter, File}
import org.junit.{Ignore, Test}

import scala.io.Source

@Ignore  // unignore to test; MediaWiki server has to be in place
class AbstractExtractorTest
{
    private val testDataRootDir = new File("core/src/test/resources/org/dbpedia/extraction/mappings")
    private val configFilePath = "extraction-framework/dump/extraction.nif.abstracts.properties"

    private val filter = new FilenameFilter
    {
        def accept(dir: File, name: String) = name endsWith "-gold.txt"
    }

    /**
     * Assumes that gold standard files end in "-gold.txt" and input files end in ".wiki"
     */
    @Test
    def testAll()
    {
        for(f <- testDataRootDir.listFiles(filter) )
        {
            test(f.getName.replace("-gold.txt", ".wiki"), f.getName)
        }
    }

    def test(fileNameWiki : String, fileNameGold : String)
    {
        println("testing wiki "+fileNameWiki+" and gold "+fileNameGold)
        val d = render(fileNameWiki)
        val g = gold(fileNameGold)
        assert(d == g, "char-diff: " + (d.length-g.length) + "\ngot:  " + d + "\ngold: " + g)
    }

    private val context = new {
        def ontology = throw new IllegalStateException("don't need Ontology for testing!!! don't call extract!")
        def language = Language.English
        def configFile : Config = new Config(ConfigUtils.loadConfig(configFilePath, "UTF-8"))
    }
    private val extractor = new AbstractExtractor(context)

    private val parser = WikiParser.getInstance()

    private def render(fileName : String) : String =
    {
        val page = new FileSource(testDataRootDir, Language.English, _ endsWith fileName).head

      //return empty Abstract in case that the parser Returned None
      //val generatedAbstract = extractor.retrievePage(n.title)
      parser(page) match {
        case Some(n) => extractor.retrievePage(page.title, page.id/*, generatedAbstract*/) match{
            case Some(l) => l
            case None => ""
        }
        case None => ""
      }
    }

    private def gold(fileName : String) : String =
    {
        Source.fromFile(testDataRootDir + "/" + fileName, "UTF-8").getLines().mkString("").replaceAll("\\s+", " ")
    }
}