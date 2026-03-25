import IRUtilities.*;
import org.mapdb.DB;

import java.io.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.SortedMap;


// lab 3 code
public class Indexer
{
    final DB db;
    public Indexer(DB _db) {
        this.db = _db;	// 用来插入indexed之后的数据？
    }

    public void process(WebPageData webpage) {
        return;
    }
//	private Porter porter;
//	private HashSet<String> stopWords;
//	public boolean isStopWord(String str)
//	{
//		return stopWords.contains(str);
//	}
//	public Indexer(String str)
//	{
//		super();
//		porter = new Porter();
//		stopWords = new HashSet<String>();
//
//		// use BufferedReader to extract the stopwords in stopwords.txt (path passed as parameter str)
//		// add them to HashSet<String> stopWords
//		// MODIFY THE BELOW CODE AND ADD YOUR CODES HERE
//		stopWords.add("is");
//		stopWords.add("am");
//		stopWords.add("are");
//		stopWords.add("was");
//		stopWords.add("were");
//	}
//	public String stem(String str)
//	{
//		return porter.stripAffixes(str);
//	}
//	public static void main(String[] arg)
//	{
//		Indexer stopStem = new Indexer("stopwords.txt");
//		String input="";
//		try{
//			do
//			{
//				System.out.print("Please enter a single English word: ");
//				BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
//				input = in.readLine();
//				if(input.length()>0)
//				{
//					if (stopStem.isStopWord(input))
//						System.out.println("It should be stopped");
//					else
//			   			System.out.println("The stem of it is \"" + stopStem.stem(input)+"\"");
//				}
//			}
//			while(input.length()>0);
//		}
//		catch(IOException ioe)
//		{
//			System.err.println(ioe.toString());
//		}
//	}
}
