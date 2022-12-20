package org.apache.lucene.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.demo.knn.DemoEmbeddings;
import org.apache.lucene.demo.knn.KnnVectorDict;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOUtils;


        public class IndexFiles implements AutoCloseable {
  static final String KNN_DICT = "knn-dict";

          // Calculates embedding vectors for KnnVector search
          private final DemoEmbeddings demoEmbeddings;
  private final KnnVectorDict vectorDict;

          private IndexFiles(KnnVectorDict vectorDict) throws IOException {
            if (vectorDict != null) {
                  this.vectorDict = vectorDict;
                  demoEmbeddings = new DemoEmbeddings(vectorDict);
                } else {
                  this.vectorDict = null;
                  demoEmbeddings = null;
                }
          }

          /** Index all text files under a directory. */
          public static void main(String[] args) throws Exception {
            String usage =
                        "java org.apache.lucene.demo.IndexFiles"
                    + " [-index INDEX_PATH] [-docs DOCS_PATH] [-update] [-knn_dict DICT_PATH]\n\n"
                    + "This indexes the documents in DOCS_PATH, creating a Lucene index"
                    + "in INDEX_PATH that can be searched with SearchFiles\n"
                    + "IF DICT_PATH contains a KnnVector dictionary, the index will also support KnnVector search";
            String indexPath = "index";
            String docsPath = null;
            String vectorDictSource = null;
            boolean create = true;
            for (int i = 0; i < args.length; i++) {
                  switch (args[i]) {
                        case "-index":
                              indexPath = args[++i];
                              break;
                        case "-docs":
                              docsPath = args[++i];
                              break;
                        case "-knn_dict":
                              vectorDictSource = args[++i];
                              break;
                        case "-update":
                              create = false;
                              break;
                        case "-create":
                              create = true;
                              break;
                        default:
                              throw new IllegalArgumentException("unknown parameter " + args[i]);
                          }
                }

            if (docsPath == null) {
                  System.err.println("Usage: " + usage);
                  System.exit(1);
                }

            final Path docDir = Paths.get(docsPath);
            if (!Files.isReadable(docDir)) {
                  System.out.println(
                              "Document directory '"
                                  + docDir.toAbsolutePath()
                                  + "' does not exist or is not readable, please check the path");
                  System.exit(1);
                }

            Date start = new Date();
            try {
                  System.out.println("Indexing to directory '" + indexPath + "'...");

                  Directory dir = FSDirectory.open(Paths.get(indexPath));
                  Analyzer analyzer = new StandardAnalyzer();
                  IndexWriterConfig iwc = new IndexWriterConfig(analyzer);

                  if (create) {
                        // Create a new index in the directory, removing any
                        // previously indexed documents:
                        iwc.setOpenMode(OpenMode.CREATE);
                      } else {
                        // Add new documents to an existing index:
                        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
                      }

                  // Optional: for better indexing performance, if you
                  // are indexing many documents, increase the RAM
                  // buffer.  But if you do this, increase the max heap
                  // size to the JVM (eg add -Xmx512m or -Xmx1g):
                  //
                  // iwc.setRAMBufferSizeMB(256.0);

                  KnnVectorDict vectorDictInstance = null;
                  long vectorDictSize = 0;
                  if (vectorDictSource != null) {
                        KnnVectorDict.build(Paths.get(vectorDictSource), dir, KNN_DICT);
                        vectorDictInstance = new KnnVectorDict(dir, KNN_DICT);
                        vectorDictSize = vectorDictInstance.ramBytesUsed();
                      }

                  try (IndexWriter writer = new IndexWriter(dir, iwc);
          IndexFiles indexFiles = new IndexFiles(vectorDictInstance)) {
                        indexFiles.indexDocs(writer, docDir);

                        // NOTE: if you want to maximize search performance,
                        // you can optionally call forceMerge here.  This can be
                        // a terribly costly operation, so generally it's only
                        // worth it when your index is relatively static (ie
                        // you're done adding documents to it):
                        //
                        // writer.forceMerge(1);
                      } finally {
                        IOUtils.close(vectorDictInstance);
                      }

                  Date end = new Date();
                  try (IndexReader reader = DirectoryReader.open(dir)) {
                        System.out.println(
                                    "Indexed "
                                        + reader.numDocs()
                                        + " documents in "
                                        + (end.getTime() - start.getTime())
                                        + " milliseconds");
                        if (reader.numDocs() > 100
                            && vectorDictSize < 1_000_000
                            && System.getProperty("smoketester") == null) {
                              throw new RuntimeException(
                                          "Are you (ab)using the toy vector dictionary? See the package javadocs to understand why you got this exception.");
                            }
                      }
                } catch (IOException e) {
                  System.out.println(" caught a " + e.getClass() + "\n with message: " + e.getMessage());
                }
          }


          void indexDocs(final IndexWriter writer, Path path) throws IOException {
            if (Files.isDirectory(path)) {
                  Files.walkFileTree(
                              path,
                              new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                  try {
                                        indexDoc(writer, file, attrs.lastModifiedTime().toMillis());
                                      } catch (
                                      @SuppressWarnings("unused")
                                      IOException ignore) {
                                        ignore.printStackTrace(System.err);
                                        // don't index files that can't be read.
                                      }
                                  return FileVisitResult.CONTINUE;
                                }
          });
                } else {
                  indexDoc(writer, path, Files.getLastModifiedTime(path).toMillis());
                }
          }

          /** Indexes a single document */
          void indexDoc(IndexWriter writer, Path file, long lastModified) throws IOException {
            try (InputStream stream = Files.newInputStream(file)) {
                  // make a new, empty document
                  Document doc = new Document();

                  // Add the path of the file as a field named "path".  Use a
                  // field that is indexed (i.e. searchable), but don't tokenize
                  // the field into separate words and don't index term frequency
                  // or positional information:
                  Field pathField = new StringField("path", file.toString(), Field.Store.YES);
                  doc.add(pathField);

                  // Add the last modified date of the file a field named "modified".
                  // Use a LongPoint that is indexed (i.e. efficiently filterable with
                  // PointRangeQuery).  This indexes to milli-second resolution, which
                  // is often too fine.  You could instead create a number based on
                  // year/month/day/hour/minutes/seconds, down the resolution you require.
                  // For example the long value 2011021714 would mean
                  // February 17, 2011, 2-3 PM.
                  doc.add(new LongPoint("modified", lastModified));

                  // Add the contents of the file to a field named "contents".  Specify a Reader,
                  // so that the text of the file is tokenized and indexed, but not stored.
                  // Note that FileReader expects the file to be in UTF-8 encoding.
                  // If that's not the case searching for special characters will fail.
                  doc.add(
                              new TextField(
                                  "contents",
                                  new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))));

                  if (demoEmbeddings != null) {
                        try (InputStream in = Files.newInputStream(file)) {
                              float[] vector =
                                          demoEmbeddings.computeEmbedding(
                                              new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
                              doc.add(
                                          new KnnVectorField("contents-vector", vector, VectorSimilarityFunction.DOT_PRODUCT));
                            }
                      }

                  if (writer.getConfig().getOpenMode() == OpenMode.CREATE) {
                        // New index, so we just add the document (no old document can be there):
                        System.out.println("adding " + file);
                        writer.addDocument(doc);
                      } else {
                        // Existing index (an old copy of this document may have been indexed) so
                        // we use updateDocument instead to replace the old one matching the exact
                        // path, if present:
                        System.out.println("updating " + file);
                        writer.updateDocument(new Term("path", file.toString()), doc);
                      }
                }
          }

          @Override
  public void close() throws IOException {
            IOUtils.close(vectorDict);
          }
}


























































