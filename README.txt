This project implements a "naive" text tagger based on Lucene / Solr, using
Lucene FST (Finite State Transducer) technology under the hood.  It is "naive"
because it does simple text word based substring tagging without consideration
of any natural language context.  It operates on the results of how you
configure text analysis in Lucene and so it's quite flexible to match things
like phonetics for sounds-like tagging if you wanted to.

For more information on Lucene FSTs, the technological marvel that enables this
component to keep ten millions place names in a structure that is a mere 175MB,
see these resources:

* https://docs.google.com/presentation/d/1Z7OYvKc5dHAXiVdMpk69uulpIT6A7FGfohjHx8fmHBU/edit#slide=id.p
* http://blog.mikemccandless.com/2010/12/using-finite-state-transducers-in.html
* http://blog.mikemccandless.com/2011/01/finite-state-transducers-part-2.html

Contributors:
  * David Smiley (MITRE)

======== Build Instructions

  * Maven (preferred):
    To compile and run tests, use:
    %> mvn test
    To compile, test, and build the jar (placed in target/), use:
    %> mvn package

  * Ant:
    To compile and build (placed in build/), use:
    %> ant

Configuration

======== Configuration

A Solr schema.xml needs:
 * A unique key field  (see <uniqueKey>).
 * A place name field marked as "stored".
 * A place name field indexed with word tokenization and other desired text
 analysis suitable for matching input text against the corpus.

The only requirement for the text analysis is that the words must be at
consecutive positions (i.e. the position increment of each term must always be
1).  So, be careful with use of stop words, synonyms, WordDelimiterFilter, and
potentially others.  You'll get a hard error from the TaggerFstCorpus when this
is violated when the FST is "built".  On the other hand, if the input text
has a position increment greater than one (such as a token exceeding the default
max character length of 255) then it is handled properly.  It's plausible that
this restriction might be lifted in the future but it is tricky -- see this for
more info:
  http://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually.html

Here is a sample field type config that should work quite well:

  <fieldType name="tag" class="solr.TextField" positionIncrementGap="100" >
    <analyzer>
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.EnglishPossessiveFilterFactory" />
      <filter class="solr.ASCIIFoldingFilterFactory"/>
      <filter class="solr.LowerCaseFilterFactory" />
    </analyzer>
  </fieldType>

When defining a field that's indexed with this type, you can choose to set
omitTermFreqAndPositions="true" omitNorms="true" since the tagger doesn't need
them.  That said, if you intend to do general keyword search on this field, then
you should not exclude those stats.

A Solr solrconfig.xml needs a special request handler, configured like this:

  <requestHandler name="/tag" class="org.opensextant.solrtexttagger.TaggerRequestHandler">
    <str name="indexedField">name_tagIdx</str>
    <str name="storedField">name</str>
    <bool name="partialMatches">false</bool>
    <str name="fq">NOT name:(of the)</str><!-- filter out -->
    <str name="cacheFile">taggerCache.dat</str>
  </requestHandler>

 * indexedField: The field that represents the corpus to match on, and that is
 indexed for tokenization.
 * storedField: (optional) Like indexedField but marked as "stored".  If
 unspecified then it is assumed to be indexedField.
 * partialMatches: A boolean that indicates whether partial word phrases should
 be considered a match for the name.  In other words, "York" would match "New
 York".
 * fq: (optional) A query that matches the subset of documents for name matching.
 * cacheFile: The file name (in the data directory) where to persist the
 dictionary that gets built.  If unspecified then it won't be persisted, and
 thus when Solr starts up the expensive data structure will need to be re-built.

======== Usage

The tagger needs to build a data structure from the index consisting mostly of
a couple of FSTs.  For ~10M place names, this used ~2GB of working RAM and ~5
minutes on a beefy server, ultimately yielding a ~175MB data structure (same
size on disk as in RAM).  Ideally this is saved to disk via the cacheFile
option, so that if Solr is restarted, it can simply read this file into memory.
If Solr's index is modified (committed), then the tagger will rebuild itself
at the next request for tagging, incurring a delay.  To move that computation
from the first tagging request to following a commit or optimize, you can
explicitly build the tagger data at an appropriate time with this URL:
  http://localhost:8983/solr/tag?build=true

For tagging, you HTTP POST data to Solr similar to how the ExtractingRequestHandler
(Tika) is invoked.  A request invoked via the "curl" program could look like this:

curl -XPOST \
  'http://localhost:8983/solr/tag?overlaps=NO_SUB&tagsLimit=5000&fl=*' \
  -H 'Content-Type:text/plain' -d @/mypath/myfile.txt

The tagger request-time parameters are:
 * overlaps: choose the algorithm to determine which overlapping tags should be
 retained, versus being pruned away.  See below...
 * matchText: A boolean indicating whether to return the matched text in the tag
 response.
 * tagsLimit: The maximum number of tags to return in the response.  Tagging
 effectively stops after this point.  By default this is 1000.
 * rows: Solr's standard param to say the maximum number of documents to return,
 but defaulting to 10000 for a tag request.
 * fl: Solr's standard param for listing the fields to return.
 * Most other standard parameters for working with Solr response formatting:
 echoParams, wt, indent, etc.

Options for the "overlaps" parameter:
 ALL: Emit all tags.
 NO_SUB: Don't emit a tag that is completely within another tag (i.e. no subtag).
 LONGEST_DOMINANT_RIGHT: Given a cluster of overlapping tags, emit the longest
  one (by character length). If there is a tie, pick the right-most. Remove
  any tags overlapping with this tag then repeat the algorithm to potentially
  find other tags that can be emitted in the cluster.

The output is broken down into two parts, first an array of tags, and then
Solr documents referenced by those tags.  Each tag has the starting character
offset, an ending character (+1) offset, and the Solr unique key field value.
The Solr documents part of the response is Solr's standard search results
format.
