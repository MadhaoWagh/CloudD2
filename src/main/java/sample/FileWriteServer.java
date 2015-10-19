package sample;

import net.*;
import sample.log.Utils;
import util.FileHelper;
import util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Chunk upload server using GFS scheme
 */
public class FileWriteServer{
	private static final Log log=Log.get();

	static class WriteResult{
		public Address address;
		public FileWriteMsgType result;

		WriteResult(Address address,FileWriteMsgType result){
			this.address=address;
			this.result=result;
		}
	}

	static class WriteServer implements MsgHandler{
		private IOControl control;
		private Path chunkDir;
		WriteServer(IOControl control,Path chunkDir) throws IOException{
			this.control=control;
			this.chunkDir=chunkDir;
		}

		//  WRITE_CHUNK, WRITE_CHUNK_CACHE
		void proc(Session session,boolean isPrimary,long start){
			String id=session.getString("id");
			final long size=session.getLong("size");
			long timeout=session.getLong("timeout");
			final long position=session.getLong("position",0);
			Address primary=session.get("primary",Address.class);   //  nullable

			UUID transID=session.get("transid",UUID.class); //  nullable
			ArrayList<Address> addresses=session.get("address",ArrayList.class);

			final SocketChannel src=session.getSocketChannel();
			File newChunk=new File(chunkDir.toFile(),id);
			Session reply=session.clone();
			reply.setType(isPrimary ? FileWriteMsgType.WRITE_FAIL : FileWriteMsgType.COMMIT_FAIL);
			do{
				if(!newChunk.exists() && position>0){
					log.w("File not exist but position is positive");
					break;
				}
				if(isPrimary){
					primary=addresses.remove(0);
					transID=UUID.randomUUID();
					reply.set("primary",primary);
					reply.set("transid",transID);
				}else{
					addresses.remove(0);
				}
				FileOutputStream fos=null;
				try{
					do{
						if(!newChunk.exists())
							newChunk.createNewFile();
						fos=new FileOutputStream(newChunk);
						final FileChannel dest=fos.getChannel();
						if(addresses.size()==0){
							//  no more forwarding
							Future<Object> writeTrans=session.getExecutor().submit(new Callable<Object>(){
								@Override
								public Object call() throws Exception{
									FileHelper.download(src,dest,size,position);
									return null;
								}
							});
							try{
								writeTrans.get(timeout+start-System.currentTimeMillis(),TimeUnit.MILLISECONDS);
								fos.close();
								if(newChunk.length()==size){
									reply.setType(isPrimary ? FileWriteMsgType.WRITE_OK : FileWriteMsgType.COMMIT_OK);
									log.i("File write to: "+newChunk.getAbsolutePath());
								}
							}catch(InterruptedException|ExecutionException|TimeoutException e){
								log.i(e);
							}
						}else{
							//  do forward
							Session forward=reply.clone();
							forward.setType(FileWriteMsgType.WRITE_CHUNK_CACHE);
							ArrayList<Address> commit_ok=new ArrayList<>();
							ArrayList<Address> commit_fail=new ArrayList<>();
							BlockingQueue<WriteResult> results=new LinkedBlockingQueue<>();
							forwardResult.put(transID,results);
							forward.set("start",start);
							control.send(forward,addresses.get(0));
							FileHelper.pipe(session.getExecutor(),
									src,dest,forward.getSocketChannel(),size,position,start,timeout);
							fos.close();
							if(newChunk.length()!=size) break;
							log.i("File write to: "+newChunk.getAbsolutePath());
							long remain;
							while((remain=start+timeout-System.currentTimeMillis())>=0){
								WriteResult r=results.poll(remain,TimeUnit.MILLISECONDS);
								if(r==null){
									commit_fail.addAll(addresses);
									commit_fail.removeAll(commit_ok);
									break;
								}else{
									if(r.result==FileWriteMsgType.COMMIT_OK)
										commit_ok.add(r.address);
									else commit_fail.add(r.address);
									if(commit_fail.size()+commit_ok.size()==addresses.size()){
										reply.setType(FileWriteMsgType.WRITE_OK);
										break;
									}
								}
							}
						}
					}while(false);
				}catch(Exception e){
					log.w(e);
					if(fos!=null)
						try{
							fos.close();
						}catch(IOException ignored){
						}
				}
			}while(false);
			try{
				if(isPrimary){
					control.response(reply,session);
				}else{
					control.send(reply,primary);
				}
			}catch(Exception e){
				log.w(e);
			}
		}

		private Map<UUID,BlockingQueue<WriteResult>> forwardResult=new ConcurrentHashMap<>();

		@Override
		public boolean process(Session session) throws IOException{
			long start=System.currentTimeMillis();
			MsgType type=session.getType();
			if(type==FileWriteMsgType.WRITE_CHUNK || type==FileWriteMsgType.WRITE_CHUNK_CACHE){
				proc(session,type==FileWriteMsgType.WRITE_CHUNK,start);
			}else if(type==FileWriteMsgType.COMMIT_OK || type==FileWriteMsgType.COMMIT_FAIL){
				UUID transID=session.get("transid",UUID.class);
				BlockingQueue<WriteResult> queue=forwardResult.get(transID);
				if(queue!=null){
					try{
						queue.put(new WriteResult(session.getSender(),(FileWriteMsgType)type));
					}catch(InterruptedException ignored){
					}
				}
			}
			return false;
		}
	}

	public static void main(String args[]){
		try{
			Utils.connectToLogServer(log);

			int port;
			if(args.length>0)
				port=Integer.parseInt(args[0]);
			else{
				log.s("No port specified!");
				return;
			}
			IOControl server=new IOControl();

			// register file upload handler
			//  modify to your dir
			MsgHandler fileWrite=new WriteServer(server,Files.createTempDirectory(Integer.toString(port)));

			MsgType[] type=FileWriteMsgType.values();
			server.registerMsgHandlerHead(fileWrite,type);
			server.registerMsgHandlerHead(new SimpleLogger(),type);
			// start server
			server.startServer(port);
			// blocking until asked to quit (see SimpleEchoClient)
			server.waitForServer();

		}catch(IOException e){
			log.w(e);
		}
	}
}
