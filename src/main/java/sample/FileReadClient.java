package sample;

import net.IOControl;
import net.Session;
import org.ini4j.Wini;
import sample.log.Utils;
import util.FileHelper;
import util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * Created by Yongtao on 9/20/2015.
 * <p/>
 * Read file from server to memory. Path is read from console
 */
public class FileReadClient{
	private static final Log log=Log.get();

	static Session downloadFile(IOControl control,String ip,int port,String path,long position,long limit) throws Exception{
		Session session=new Session(FileReadMsgType.READ_FILE);
		session.set("path",path);
		if(position>0)
			session.set("position",position);
		if(limit>0)
			session.set("limit",limit);
		return control.request(session,ip,port);
	}

	static String downloadToTemp(Path tempDir,IOControl control,String ip,int port,String path){
		try{
			Session response=downloadFile(control,ip,port,path,0,0);
			if(response.getType()!=FileReadMsgType.READ_FILE_OK) return null;
			File newFile=new File(tempDir.toFile(),response.getString("name"));
			newFile.createNewFile();
			FileOutputStream fos=new FileOutputStream(newFile);
			FileHelper.download(response.getSocketChannel(),fos.getChannel(),response.getLong("size"));
			fos.close();
			return newFile.getAbsolutePath();
		}catch(Exception e){
			log.w(e);
			return null;
		}
	}
	static long readFile(IOControl control,String ip,int port,String path,long position,long limit){
		try{
			Session response=downloadFile(control,ip,port,path,position,limit);
			if(response.getType()!=FileReadMsgType.READ_FILE_OK) return 0;
			long size=response.getLong("size");
			SocketChannel src=response.getSocketChannel();
			ByteBuffer buffer=ByteBuffer.allocateDirect(1024*128);
			long read=0;
			while(read<size){
				long read_once=0;
				while(buffer.hasRemaining() && read<size){
					read_once=src.read(buffer);
					if(read_once<0) break;
					read+=read_once;
				}
				if(read_once<0) break;
				if(read<size)
					buffer.reset();
			}
			return read;
		}catch(Exception e){
			log.w(e);
			return 0;
		}
	}
	public static void main(String args[]){
		try{
			Utils.connectToLogServer(log);

			//  read conf file here
			Wini conf=new Wini(new File("conf/sample/sample.ini"));
			String serverIP=conf.get("read server","ip");
			int serverPort=conf.get("read server","port",int.class);

			try{
				IOControl control=new IOControl();
				Path tempDir=Files.createTempDirectory(null);
				//  get what you type
				Scanner in=new Scanner(System.in);
				Path temp=Files.createTempDirectory(null);
				for(;;){
					String cmd=in.nextLine();
					if(cmd.length()>0){
						String[] tokens=cmd.trim().split("\\s");
						if(tokens.length==1){
							//  download to temp
							log.i("Down to: "+downloadToTemp(tempDir,control,serverIP,serverPort,tokens[0]));
						}else if(tokens.length==2){
							String pre=tokens[0].toLowerCase();
							if(pre=="read" || pre=="r"){
								log.i("Read: "+readFile(control,serverIP,serverPort,tokens[1],0,0));
							}else if(pre=="download" || pre=="down" || pre=="d"){
								log.i("Down to: "+downloadToTemp(tempDir,control,serverIP,serverPort,tokens[0]));
							}else log.i("False cmd format");
						}else if(tokens.length==3){
							String pre=tokens[0].toLowerCase();
							if(pre=="read" || pre=="r"){
								try{
									long position=Long.parseLong(tokens[2]);
									log.i("Read: "+readFile(control,serverIP,serverPort,tokens[1],position,0));
								}catch(NumberFormatException e){
									log.i("position not recognized.");
								}
							}else log.i("False cmd format");
						}else if(tokens.length==4){
							String pre=tokens[0].toLowerCase();
							if(pre=="read" || pre=="r"){
								try{
									long position=Long.parseLong(tokens[2]);
									long limit=Long.parseLong(tokens[3]);
									log.i("Read: "+readFile(control,serverIP,serverPort,tokens[1],position,limit));
								}catch(NumberFormatException e){
									log.i("position not recognized.");
								}
							}else log.i("False cmd format");
						}else log.i("Unkown cmd.");
					}
				}
			}catch(Exception e){
				log.w(e);
			}
		}catch(IOException e){
			log.w(e);
		}
	}
}
