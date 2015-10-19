package sample;

import net.Address;
import net.IOControl;
import net.Session;
import sample.log.Utils;
import util.FileHelper;
import util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Scanner;

public class FileWriteClient{
	private static final Log log=Log.get();
	static long timeout=60*1000;    //  60 seconds

	static boolean upload(IOControl control,String path,ArrayList<Address> addresses,long position){
		try{
			File file=new File(path);
			FileInputStream fis=new FileInputStream(file);
			FileChannel src=fis.getChannel();
			Session req=new Session(FileWriteMsgType.WRITE_CHUNK);
			String id=file.getName();
			long size=file.length();
			req.set("id",id);
			req.set("size",size);
			req.set("timeout",timeout);
			req.set("address",addresses);
			if(position>0)
				req.set("position",position);
			control.send(req,addresses.get(0));
			SocketChannel dest=req.getSocketChannel();
			FileHelper.upload(src,dest,size);
			fis.close();
			Session result=control.get(req);
			return result.getType()==FileWriteMsgType.WRITE_OK;
		}catch(Exception e){
			log.w(e);
			return false;
		}
	}
	static boolean upload(IOControl control,String path,ArrayList<Address> addresses){
		return upload(control,path,addresses,0);
	}
	static ArrayList<Address> splitAddress(String[] tokens,int start){
		ArrayList<Address> result=new ArrayList<>();
		for(int i=start;i<tokens.length;++i){
			String[] parts=tokens[i].split(":");
			if(parts.length!=2) return null;
			try{
				int port=Integer.parseInt(parts[1]);
				Address address=new Address(parts[0],port);
				result.add(address);
			}catch(NumberFormatException e){return null;}
		}
		return result;
	}

	public static void main(String args[]){
		try{
			Utils.connectToLogServer(log);
			try{
				IOControl control=new IOControl();
				//  get what you type
				Scanner in=new Scanner(System.in);
				for(;;){
					String cmd=in.nextLine();
					if(cmd.length()>0){
						String line=cmd.trim();
						String[] tokens=line.split("\\s");
						if(tokens.length>1){
							ArrayList<Address> addresses=splitAddress(tokens,1);
							if(addresses!=null){
								if(upload(control,tokens[0],addresses))
									log.i("File upload success.");
								else
									log.i("File upload fails.");
								continue;
							}
						}
						log.i("Input local file name and list of servers");
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
