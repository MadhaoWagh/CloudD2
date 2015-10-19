package sample;

import net.IOControl;
import net.MsgFilter;
import net.MsgHandler;
import net.Session;
import org.ini4j.Wini;
import sample.log.Utils;
import util.FileHelper;
import util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

/**
 * Created by Yongtao on 9/10/2015.
 * <p/>
 * This is demo for using IOControl as server.
 */
public class FileReadEchoServer{
	private static Log log=Log.get();

	/**
	 * Deal with echo and exit msg
	 */
	static class Echo implements MsgHandler{
		private IOControl control;
		Echo(IOControl control){
			this.control=control;
		}
		@Override
		public boolean process(Session session) throws IOException{
			control.response(new Session(EchoMsgType.ACK),session);
			if(session.getType()==EchoMsgType.EXIT_SERVER)
				control.quitServer();
			return false;
		}
	}

	/**
	 * Handle file read.
	 */
	static class FileServer implements MsgHandler{
		private IOControl control;

		FileServer(IOControl control){
			this.control=control;
		}

		@Override
		public boolean process(Session session) throws IOException{
			String path=session.getString("path");
			File file;
			Session error=new Session(FileReadMsgType.READ_FILE_ERROR);
			try{
				file=new File(path);
				FileInputStream fis=new FileInputStream(file);
				Session reply=new Session(FileReadMsgType.READ_FILE_OK);
				long fileSize=file.length();
				long position=session.getLong("position",0);
				long limit=session.getLong("limit",fileSize);
				if(limit>fileSize) limit=fileSize;
				reply.set("name",file.getName());
				reply.set("size",limit);
				reply.set("modify",file.lastModified());
				control.response(reply,session);
				SocketChannel channel=session.getSocketChannel();
				FileChannel fc=fis.getChannel();
				//  here is DMA copying utilizing sendfile system call.
				FileHelper.upload(fc,channel,limit,position);
			}catch(Exception e){
				log.w(e);
				error.set("comment",e.getMessage());
				control.response(error,session);
			}
			return false;
		}
	}

	public static void main(String args[]){
		try{
			Utils.connectToLogServer(log);
			//  read conf
			Wini conf=new Wini(new File("conf/sample/sample.ini"));
			int port=conf.get("read server","port",int.class);

			try{
				IOControl server=new IOControl();
				//  register echo handlers
				MsgHandler logger=new Echo(server);
				server.registerMsgHandlerLast(logger,new EchoMsgType[]{EchoMsgType.ECHO,EchoMsgType.EXIT_SERVER});

				// register filters
				MsgFilter stat=new RawLogger();
				server.registerMsgFilterHead(stat);

				// register file read handler
				MsgHandler fileRead=new FileServer(server);
				server.registerMsgHandlerHead(fileRead,FileReadMsgType.READ_FILE);

				// start server
				server.startServer(port);

				// blocking until asked to quit (see SimpleEchoClient)
				server.waitForServer();
			}catch(IOException e){
				log.w(e);
			}
		}catch(IOException e){
			log.w(e);
		}
	}
}
