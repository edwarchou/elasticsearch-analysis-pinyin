/*
* Licensed to ElasticSearch and Shay Banon under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. ElasticSearch licenses this
* file to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.elasticsearch.index.analysis;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.huaban.analysis.jieba.WordDictionary;
import junit.framework.Assert;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.elasticsearch.analysis.PinyinConfig;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.*;


class DgJiebaAdapter implements Iterator<SegToken> {
    private static final JiebaSegmenter jiebaTagger = new JiebaSegmenter();
    private final JiebaSegmenter.SegMode segMode;
    private Iterator<SegToken> tokens;
    private String raw = null;

    public DgJiebaAdapter(String segModeName) {
        System.out.println("init jieba adapter");
        if (null == segModeName) {
            this.segMode = JiebaSegmenter.SegMode.SEARCH;
        } else {
            this.segMode = JiebaSegmenter.SegMode.valueOf(segModeName);
        }

    }

    public synchronized void reset(Reader input) {
        try {
            StringBuilder bdr = new StringBuilder();
            char[] buf = new char[1024];
            boolean var4 = false;

            int size;
            while((size = input.read(buf, 0, buf.length)) != -1) {
                String tempstr = new String(buf, 0, size);
                bdr.append(tempstr);
            }

            this.raw = bdr.toString().trim();
        } catch (IOException var6) {
            var6.printStackTrace();
        }

        List<SegToken> list = jiebaTagger.process(this.raw, this.segMode);
        list.sort((o1, o2) -> {
            if (o1.startOffset != o2.startOffset) {
                return o1.startOffset - o2.startOffset;
            }
            if (o1.endOffset != o2.endOffset) {
                return o2.endOffset - o1.endOffset;
            }
            return 0;
        });
        this.tokens = list.iterator();
    }

    public boolean hasNext() {
        return this.tokens.hasNext();
    }

    public SegToken next() {
        return (SegToken)this.tokens.next();
    }

    public void remove() {
        this.tokens.remove();
    }
}


final class JiebaTokenizer extends Tokenizer {
    private CharTermAttribute termAtt = (CharTermAttribute)this.addAttribute(CharTermAttribute.class);
    private OffsetAttribute offsetAtt = (OffsetAttribute)this.addAttribute(OffsetAttribute.class);
    private TypeAttribute typeAtt = (TypeAttribute)this.addAttribute(TypeAttribute.class);
    private PositionIncrementAttribute positionIncrementAttribute = (PositionIncrementAttribute)this.addAttribute(PositionIncrementAttribute.class);
    private int endPosition;
    private SegToken lastToken = null;
    private Map<Integer, Integer> endOffset2PosIncr = new HashMap();
    private DgJiebaAdapter jieba;

    protected JiebaTokenizer(String segModeName) {
        this.jieba = new DgJiebaAdapter(segModeName);
    }

    public boolean incrementToken() throws IOException {
        this.clearAttributes();
        if (this.jieba.hasNext()) {
            SegToken token = this.jieba.next();
            this.termAtt.append(token.word);
            this.termAtt.setLength(token.word.length());
            this.offsetAtt.setOffset(token.startOffset, token.endOffset);
            Integer posIncr = 0;
            if (null == this.lastToken) {
                posIncr = 1;
            } else if (token.word.startsWith(this.lastToken.word)) {
                posIncr = 0;
            } else if (this.endOffset2PosIncr.containsKey(token.startOffset)) {
                posIncr = 1;
            } else if (token.endOffset <= this.lastToken.endOffset) {
                posIncr = 0;
            } else {
                posIncr = 0;
            }

            this.positionIncrementAttribute.setPositionIncrement(posIncr);
            this.endOffset2PosIncr.put(token.endOffset, posIncr);
            this.endPosition = token.endOffset;
            this.lastToken = token;
            return true;
        } else {
            return false;
        }
    }

    public void end() throws IOException {
        int finalOffset = this.correctOffset(this.endPosition);
        this.offsetAtt.setOffset(finalOffset, finalOffset);
    }

    public void reset() throws IOException {
        super.reset();
        this.jieba.reset(this.input);
        this.lastToken = null;
        this.endOffset2PosIncr = new HashMap();
    }
}


final class JiebaAnalyzer extends Analyzer {
    private String segMode;

    public JiebaAnalyzer() {
        this(JiebaSegmenter.SegMode.INDEX.name());
    }

    public JiebaAnalyzer(String segMode) {
        this.segMode = segMode;
    }

    public JiebaAnalyzer(ReuseStrategy reuseStrategy) {
        super(reuseStrategy);
    }

    protected TokenStreamComponents createComponents(String fieldName) {
        return new TokenStreamComponents(new JiebaTokenizer(this.segMode));
    }
}

public class DgPinyinAnalysisTest {


    @Test
    public void testTokenFilter() throws IOException {
        PinyinConfig config = new PinyinConfig();
        config.keepJoinedFullPinyin = true;
        config.lowercase = true;
        config.keepOriginal = true;
        config.keepFirstLetter = false;
        config.keepSeparateFirstLetter = false;
        config.LimitFirstLetterLength = 16;
        config.keepFullPinyin = false;
//        config.keepNoneChinese = true;
        config.keepNoneChineseInFirstLetter = true;
        config.noneChinesePinyinTokenize = false;


        StringReader sr = new StringReader("search我们是招商证券");
        Analyzer analyzer = new JiebaAnalyzer();
        System.out.println(Paths.get("/dic").toFile());
        WordDictionary.getInstance().init(Paths.get("/Users/zhoumingxing/Documents/github/elasticsearch-analysis-pinyin/src/main/dic").toFile());
//        JiebaDict.init(new Environment(Settings.EMPTY, Paths.get("/dic")));
//        analyzer = new WhitespaceAnalyzer();
        DgPinyinTokenFilter filter = new DgPinyinTokenFilter(analyzer.tokenStream("f", sr), config);
//        filter = analyzer.tokenStream("f", sr);
        filter.reset();
        System.out.println();
        List<String> pinyin = getTokenFilterResult(filter);


        sr = new StringReader("我们是招商证券，还有谁？");
        analyzer = new JiebaAnalyzer();
        WordDictionary.getInstance().init(Paths.get("/Users/zhoumingxing/Documents/github/elasticsearch-analysis-pinyin/src/main/dic").toFile());
        System.out.println(Paths.get("/dic").toFile());
//        JiebaDict.init(new Environment(Settings.EMPTY, Paths.get("/dic")));
//        analyzer = new WhitespaceAnalyzer();
        pinyin.clear();
        filter = new DgPinyinTokenFilter(analyzer.tokenStream("f", sr), config);
//        filter = analyzer.tokenStream("f", sr);
        filter.reset();
        System.out.println();
        pinyin = getTokenFilterResult(filter);



//        Assert.assertEquals(9, pinyin.size());
//        Assert.assertEquals("liu", pinyin.get(0));
//        Assert.assertEquals("d", pinyin.get(1));
//        Assert.assertEquals("de", pinyin.get(2));
//        Assert.assertEquals("hua", pinyin.get(3));
//        Assert.assertEquals("m", pinyin.get(4));
//        Assert.assertEquals("ming", pinyin.get(5));
//        Assert.assertEquals("z", pinyin.get(6));
//        Assert.assertEquals("zi", pinyin.get(7));
//        Assert.assertEquals("mz", pinyin.get(8));


    }

    private List<String> getTokenFilterResult(DgPinyinTokenFilter filter)  throws IOException {
        List<String> pinyin = new ArrayList<String>();
        int pos=0;
        while (filter.incrementToken()) {
            CharTermAttribute ta = filter.getAttribute(CharTermAttribute.class);
            OffsetAttribute offset = filter.getAttribute(OffsetAttribute.class);
            PositionIncrementAttribute position = filter.getAttribute(PositionIncrementAttribute.class);
            pos=pos+position.getPositionIncrement();
            pinyin.add(ta.toString());
            Assert.assertTrue("startOffset must be non-negative",offset.startOffset()>=0);
            Assert.assertTrue("endOffset must be >= startOffset",offset.startOffset()>=0);
            System.out.println(ta.toString()+","+offset.startOffset()+","+offset.endOffset()+","+pos);
        }
        return pinyin;
    }

}
