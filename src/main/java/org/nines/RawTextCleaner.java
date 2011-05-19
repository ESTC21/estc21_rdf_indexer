package org.nines;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.mozilla.intl.chardet.nsDetector;
import org.mozilla.intl.chardet.nsICharsetDetectionObserver;

/**
 * Cleaner for Raw text files. It will clean out unused tags,
 * fix escape sequences and strip bad utf-8 characters. Errors
 * and changes will be written out to the log files. The cleaned file
 * will be written out to the fullltext area of solr sources
 * 
 * @author loufoster
 *
 */
public class RawTextCleaner {

    private ErrorReport errorReport;    
    private RDFIndexerConfig config;
    private Logger log;
    private String fileEncoding;
    private long totalOrigChars = 0;
    private long totalFilesChanged = 0;
    private long totalCleanedChars = 0;
    
    public RawTextCleaner( RDFIndexerConfig config, ErrorReport errorReport ) {
        this.errorReport = errorReport;
        this.config = config;
        this.log = Logger.getLogger(RawTextCleaner.class.getName());
    }
    
    /**
     * Clean thespecifled file and write the results to the fulltext folder.
     * Errors will be added to the <code>errorReport</code>
     * 
     * @param rawTextFile
     * @param errorReport
     */
    public void clean( final File rawTextFile ) {
    
        this.log.info("Clean raw text from file "+rawTextFile);
        
        // get the filename for the cleaned fulltext file
        File cleanTextFile = toFullTextFile(rawTextFile);
        
        // ensure that the file is UTF-8 encoded...
        File srcFile = rawTextFile;
        try {
            srcFile = fixEncoding(rawTextFile, cleanTextFile);
        } catch (IOException e) {
            this.errorReport.addError( 
                new IndexerError(rawTextFile.toString(), "", "Unable to convert raw text file encoding to UTF-8: " + e.toString()));
            return;
        }
        
        // ...now read the file
        String content = null;
        InputStreamReader is = null;
        try {
            is = new InputStreamReader(new FileInputStream(srcFile), "UTF-8");
            content =  IOUtils.toString(is);
        } catch ( Exception e ) {
            this.errorReport.addError( 
                new IndexerError(rawTextFile.toString(), "", "Unable to read raw text file: " + e.toString()));
            return;
            
        } finally {
            IOUtils.closeQuietly(is);
        }
        
        // stats!
        long startChars = content.length();;
        this.totalOrigChars += startChars;
        
        // clean it up as best as possible
        content = cleanText( content );
        content = TextUtils.stripEscapeSequences(content, errorReport, rawTextFile); 
        content = TextUtils.normalizeWhitespace(content);
        content = TextUtils.stripUnknownUTF8(content, this.errorReport, rawTextFile); 
        
        long endChars = content.length();
        this.totalCleanedChars += endChars;
        if ( endChars != startChars ) {
            this.totalFilesChanged++;
        }
        this.log.info("  => Original length: "+startChars+", Cleaned length: "+endChars+", Delta:"+(startChars - endChars) );
                
        // Make sure that the directory structure exists
        if ( cleanTextFile.getParentFile().exists() == false) {
            if ( cleanTextFile.getParentFile().mkdirs() == false ) {
                this.errorReport.addError(
                    new IndexerError(cleanTextFile.toString(), "", "Unable to create full text directory tree"));
                return;
            }
        }
        
        // dump the content
        Writer outWriter = null;
        try {
            outWriter = new OutputStreamWriter(new FileOutputStream(cleanTextFile), "UTF-8");
            outWriter.write( content );
        } catch (IOException e) {
            this.errorReport.addError( 
                new IndexerError(cleanTextFile.toString(), "", "Unable to write cleaned text file: " + e.toString()));
        } finally {
            IOUtils.closeQuietly(outWriter);
        }
    }
    
    private File fixEncoding(File rawTextFile, File cleanTextFile) throws IOException {
        
        // detect the encoding of the file....
        nsDetector det = new nsDetector();
        det.Init(new nsICharsetDetectionObserver() {
            public void Notify(String charset) {
                RawTextCleaner.this.fileEncoding = charset;
            }
        });

        BufferedInputStream imp = new BufferedInputStream(new FileInputStream(rawTextFile));
        byte[] buf = new byte[1024];
        int len;
        boolean done = false;
        boolean isAscii = true;
        while ((len = imp.read(buf, 0, buf.length)) != -1) {
            if (isAscii) {
                isAscii = det.isAscii(buf, len);
            }
            if (!isAscii && !done) {
                done = det.DoIt(buf, len, false);
            }
        }
        det.DataEnd();
        imp.close();

        // if it is not utf-8, attempt to convert it!
        if (this.fileEncoding.equalsIgnoreCase("UTF-8") == false) {
            this.log.info("  * Converting " + rawTextFile.toString() + " from " + this.fileEncoding + " to UTF-8");
            
            // read from original encoding into 16-bit unicode
            String nonUtf8Txt = IOUtils.toString(new FileInputStream(rawTextFile), this.fileEncoding);
            
            // setup encoders to translate the data. IF bad chars
            // are encountered, replace them with 0xFFFD (uunkown utf-8 symbol)
            Charset utf8cs = Charset.availableCharsets().get("UTF-8");
            CharsetEncoder utf8en = utf8cs.newEncoder();
            utf8en.onMalformedInput(CodingErrorAction.REPLACE);
            utf8en.onUnmappableCharacter(CodingErrorAction.REPLACE);
            
            // encode the 16-bit unicode to UTF-8 and write out the bytes
            ByteBuffer utf8Buffer = utf8en.encode(CharBuffer.wrap(nonUtf8Txt));
            FileOutputStream fos = new FileOutputStream(cleanTextFile);
            fos.write(utf8Buffer.array());
            fos.close();

            return cleanTextFile;
        }

        return rawTextFile;
    }

    private File toFullTextFile(File rawTextFile) {
        String cleanedFile = this.config.sourceDir.toString().replace("rawtext", "fulltext") 
            + "/" + SolrClient.safeCore(this.config.archiveName);
        return new File(cleanedFile +"/" + rawTextFile.getName());   
    }
    
    public long getTotalFilesChanged() {
        return this.totalFilesChanged;
    }
    
    public long getOriginalLength() {
        return this.totalOrigChars;
    }
    
    public long getCleanedLength() {
        return this.totalCleanedChars;
    }
    
    /**
     * Strip html-ish markup from text
     * @param fullText
     * @return
     */
    private String cleanText( String fullText ) {

        // remove everything between <head>...</head>
        fullText = removeTag(fullText, "head");

        // remove everything between <script>..</script>
        fullText = removeTag(fullText, "script");

        // remove everything between <...>
        fullText = removeBracketed(fullText, "<", ">");

        // Get rid of non-unix line endings
        fullText = fullText.replaceAll("\r", "");

        return fullText;
    }
    
    private String removeBracketed(String fullText, String left, String right) {
        int start = fullText.indexOf(left);
        while (start != -1) {
            int end = fullText.indexOf(right, start);
            if (end == -1) {
                start = -1;
            } else {
                fullText = fullText.substring(0, start) + "\n" + fullText.substring(end + right.length());
                start = fullText.indexOf(left);
            }
        }
        return fullText;
    }

    private String removeTag(String fullText, String tag) {
        return removeBracketed(fullText, "<" + tag, "</" + tag + ">");
    }
}