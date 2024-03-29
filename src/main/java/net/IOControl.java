package net;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import util.Log;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Yongtao on 8/28/2015.
 * <p/>
 * This is the heart of msg processing over socket.
 * After initializing IOControl object, you can add multiple filters/handlers using registerXXX methods.
 * <p/>
 * Filters are invoked before session info is acquired in order from head to last.
 * Handlers are invoked after session info is acquired in order from head to last.
 * Filter/handler registration/unregistration are NOT thread safe, they should be done before
 * server starts and any IO operations.
 * <p/>
 * IOControl can be run in client or server mode, both support filter/handler.
 * If startServer is called, IOControl starts server. Client IO operations can be called in either mode.
 */
public class IOControl{
	private static final Log log=Log.get();
	private static int maxRetry=3;
	private final GenericKeyedObjectPool<Address,SocketChannel> socketPool=new GenericKeyedObjectPool<>(new SocketPoolFactory());
	private Map<MsgType,ArrayList<MsgHandler>> handlerChain=new HashMap<>();
	private List<MsgFilter> filters=new ArrayList<>();
	private BlockingQueue<InternalCmd> exitQueue=new LinkedBlockingQueue<>();
	private Queue<InternalCmd> cmdQueue=new ConcurrentLinkedQueue<>();
	private ExecutorService pool=Executors.newCachedThreadPool();
	private Selector selector;
	private AtomicBoolean startMark=new AtomicBoolean(false);
	private Queue<SocketChannel> returnQueue=new ConcurrentLinkedDeque<>();

	public ExecutorService getPool(){
		return pool;
	}

	public boolean registerMsgFilterHead(MsgFilter filter){
		if(startMark.get()) throw new IllegalStateException();
		if(filters.indexOf(filter)>0) return false;
		filters.add(0,filter);
		return true;
	}

	public boolean registerMsgFilterLast(MsgFilter filter){
		if(startMark.get()) throw new IllegalStateException();
		if(filters.indexOf(filter)>0) return false;
		filters.add(filter);
		return true;
	}

	public boolean unregisterMsgFilter(MsgFilter filter){
		if(startMark.get()) throw new IllegalStateException();
		return filters.remove(filter);
	}

	public boolean registerMsgHandlerHead(MsgHandler handler,MsgType type){
		return register(handler,type,false);
	}

	public boolean registerMsgHandlerHead(MsgHandler handler,Enumeration<MsgType> types){
		boolean result=true;
		while(types.hasMoreElements()){
			if(!register(handler,types.nextElement(),false)) result=false;
		}
		return result;
	}

	public boolean registerMsgHandlerHead(MsgHandler handler,MsgType[] types){
		boolean result=true;
		for(int i=types.length-1;i>=0;--i){
			if(!register(handler,types[i],false)) result=false;
		}
		return result;
	}

	public boolean registerMsgHandlerLast(MsgHandler handler,MsgType type){
		return register(handler,type,true);
	}

	public boolean registerMsgHandlerLast(MsgHandler handler,Enumeration<MsgType> types){
		boolean result=true;
		while(types.hasMoreElements()){
			if(!register(handler,types.nextElement(),true)) result=false;
		}
		return result;
	}

	public boolean registerMsgHandlerLast(MsgHandler handler,MsgType[] types){
		boolean result=true;
		for(MsgType type : types){
			if(!register(handler,type,true)) result=false;
		}
		return result;
	}

	public boolean unregisterMsgHandler(MsgHandler handler,Enumeration<MsgType> types){
		boolean result=true;
		while(types.hasMoreElements()){
			if(!unregister(handler,types.nextElement())) result=false;
		}
		return result;
	}

	public boolean unregisterMsgHandler(MsgHandler handler,MsgType[] types){
		boolean result=true;
		for(int i=types.length-1;i>=0;--i){
			if(!unregister(handler,types[i])) result=false;
		}
		return result;
	}

	public boolean unregisterMsgHandler(MsgHandler handler,MsgType type){
		return unregister(handler,type);
	}

	private boolean unregister(MsgHandler handler,MsgType type){
		if(startMark.get()) throw new IllegalStateException();
		ArrayList<MsgHandler> handlerList=handlerChain.get(type);
		if(handlerList==null || !handlerList.contains(handler)) return false;
		handlerList.remove(handler);
		return true;
	}

	private boolean register(MsgHandler handler,MsgType type,boolean last){
		if(startMark.get()) throw new IllegalStateException();
		ArrayList<MsgHandler> handlerList=handlerChain.get(type);
		if(handlerList==null){
			handlerList=new ArrayList<>();
			handlerChain.put(type,handlerList);
		}
		if(!handlerList.contains(handler)){
			if(last)
				handlerList.add(handler);
			else
				handlerList.add(0,handler);
			return true;
		}
		return false;
	}

	private void forwardToHandler(Session session) throws IOException{
		try{
			ObjectInputStream ois=new ObjectInputStream(session.getSocket().getInputStream());
			Session readOut=(Session)ois.readObject();
			session.copy(readOut);
			MsgType type=session.getType();
			List<MsgHandler> handlers=handlerChain.get(type);
			if(handlers!=null){
				for(MsgHandler handler : handlers){
					if(!handler.process(session)) break;
				}
			}
		}catch(ClassNotFoundException e){
			throw new IllegalStateException();
		}
	}

	/**
	 * If you change MsgType, call this method to forward to new MsgType handlers.
	 * If MsgType is unchanged, the whole handler chain will still be called.
	 *
	 * @param session Modified session.
	 * @throws IOException
	 */
	public void forward(Session session) throws IOException{
		MsgType type=session.getType();
		List<MsgHandler> handlers=handlerChain.get(type);
		if(handlers!=null){
			for(MsgHandler handler : handlers){
				if(!handler.process(session)) break;
			}
		}
	}

	private Session process(SocketChannel socketChannel) throws IOException{
		Session raw=new Session(socketChannel,pool);
		if(!filters.isEmpty()){
			MsgFilter head=filters.get(0);
			head.doFilter(raw,new FilterChainImpl());
		}else forwardToHandler(raw);
		return raw;
	}

	/**
	 * Start listening to port and all registered filters/handlers will be called if there's incoming connection.
	 *
	 * @param port Port to listen.
	 * @throws IOException
	 */
	public void startServer(int port) throws IOException{
		if(startMark.get()) throw new IllegalStateException();
		ServerSocketChannel server=ServerSocketChannel.open();
		selector=Selector.open();
		server.socket().bind(new InetSocketAddress(port));
		server.configureBlocking(false);
		server.register(selector,SelectionKey.OP_ACCEPT);
		Coordinator coordinator=new Coordinator(selector);
		pool.execute(coordinator);
		if(!startMark.compareAndSet(false,true)) throw new IllegalStateException();
	}

	/**
	 * Send internal control command to coordinator. You also need to add logic in class Coordinator.
	 *
	 * @param cmd
	 */
	public void notifyServer(InternalCmd cmd){
		if(!startMark.get()) throw new IllegalStateException();
		cmdQueue.add(cmd);
		selector.wakeup();
	}

	/**
	 * Wait for server fully exits.
	 */
	public void waitForServer(){
		if(!startMark.get()) throw new IllegalStateException();
		try{
			exitQueue.take();
			pool.shutdown();
		}catch(InterruptedException e){
			e.printStackTrace();
		}
	}

	/**
	 * Quit server gracefully.
	 */
	public void quitServer(){
		if(!startMark.get()) throw new IllegalStateException();
		quitServer(false);
	}

	/**
	 * Quit server.
	 *
	 * @param forceShutdown False for shutting down server gracefully, waiting for all ongoing filter/handler threads
	 *                      to finish their jobs.
	 *                      True if shutting down server by closing thread pool. Note once brutally shut down, the
	 *                      IOControl object should NOT be reused.
	 */
	public void quitServer(boolean forceShutdown){
		if(!startMark.get()) throw new IllegalStateException();
		if(forceShutdown){
			pool.shutdown();
			exitQueue.add(new InternalCmd(InternalCmd.CMD.OK));
			pool=Executors.newCachedThreadPool();
		}else{
			cmdQueue.add(new InternalCmd(InternalCmd.CMD.EXIT));
			selector.wakeup();
		}
	}

	/**
	 * Send msg to ip:port once. Connection may be pooled.
	 * This is client IO operation.
	 * Connected socket and channel will be write to outgoing session
	 *
	 * @param session Msg to send.
	 * @param ip      Target ip
	 * @param port    Target port
	 * @throws Exception If operation cannot be done.
	 */
	public void send(Session session,String ip,int port) throws Exception{
		SocketChannel cachedSocket;
		Address address=new Address(ip,port);
		IOException lastIO=null;
		Exception lastE=null;
		for(int i=0;i<maxRetry;++i){
			try{
				cachedSocket=socketPool.borrowObject(address);
			}catch(Exception e){
				lastE=e;
				continue;
			}
			try{
				ObjectOutputStream oos=new ObjectOutputStream(cachedSocket.socket().getOutputStream());
				oos.writeObject(session);
				oos.flush();
				session.setSocketChannel(cachedSocket);
				session.setSocket(cachedSocket.socket());
				socketPool.returnObject(address,cachedSocket);
				return;
			}catch(IOException e){
				lastIO=e;
				try{
					socketPool.invalidateObject(address,cachedSocket);
				}catch(Exception e1){
					lastE=e1;
				}
			}
		}
		if(lastIO!=null)
			throw lastIO;
		else throw lastE;
	}

	public void send(Session session,Address address) throws Exception{
		send(session,address.getIp(),address.getPort());
	}

	/**
	 * Get response from previously sent session.
	 *
	 * @param session previously sent session
	 * @return response
	 * @throws IOException
	 */
	public Session get(Session session) throws IOException{
		SocketChannel socketChannel=session.getSocketChannel();
		return process(socketChannel);
	}

	/**
	 * Reply to oldSession. If you send reply, the handler should return false so keep request/response pair consistent.
	 *
	 * @param newSession Msg to send.
	 * @param oldSession Previously received msg.
	 * @throws IOException If that happens, consider transaction has failed.
	 */
	public void response(Session newSession,Session oldSession) throws IOException{
		Socket oldSocket=oldSession.getSocket();
		ObjectOutputStream oos=new ObjectOutputStream(oldSocket.getOutputStream());
		oos.writeObject(newSession);
	}

	/**
	 * Demand reply for sent msg.
	 *
	 * @param session Msg to send.
	 * @param ip      Target ip
	 * @param port    Target port
	 * @return Replied msg
	 * @throws Exception
	 */
	public Session request(Session session,String ip,int port) throws Exception{
		send(session,ip,port);
		SocketChannel socketChannel=session.getSocketChannel();
		return process(socketChannel);
	}

	/**
	 * Same as request(Session session,String ip,int port).
	 * Only ip and port info is used from old session,
	 * the underlying connection is not guaranteed to be reused.
	 *
	 * @param session    Msg to send
	 * @param oldSession Previously received msg
	 * @return Replied msg
	 * @throws Exception
	 */
	public Session request(Session session,Session oldSession) throws Exception{
		String ip=oldSession.getSocket().getInetAddress().getHostAddress();
		int port=oldSession.getSocket().getPort();
		return request(session,ip,port);
	}

	public Session request(Session session,Address address) throws Exception{
		return request(session,address.getIp(),address.getPort());
	}

	static class SocketPoolFactory extends BaseKeyedPooledObjectFactory<Address,SocketChannel>{
		@Override
		public SocketChannel create(Address address) throws Exception{
			return SocketChannel.open(new InetSocketAddress(address.getIp(),address.getPort()));
		}

		@Override
		public PooledObject<SocketChannel> wrap(SocketChannel socketChannel){
			return new DefaultPooledObject<>(socketChannel);
		}

		@Override
		public void destroyObject(Address key,PooledObject<SocketChannel> p) throws Exception{
			try{
				p.getObject().close();
			}catch(IOException ignored){
			}
			super.destroyObject(key,p);
		}

		@Override
		public boolean validateObject(Address key,PooledObject<SocketChannel> p){
			if(super.validateObject(key,p)){
				SocketChannel socket=p.getObject();
				if(socket.isConnected()) return true;
			}
			return false;
		}
	}

	class FilterChainImpl implements FilterChain{
		private int position=0;

		@Override
		public void doFilter(Session session,MsgFilter pre) throws IOException{
			try{
				MsgFilter preGet=filters.get(position);
				if(preGet!=pre) throw new IllegalStateException();
				++position;
				if(position==filters.size()){
					forwardToHandler(session);
				}else{
					MsgFilter next=filters.get(position);
					next.doFilter(session,this);
				}
			}catch(IndexOutOfBoundsException e){
				throw new IllegalStateException();
			}
		}
	}

	class Coordinator implements Runnable{
		private final Semaphore semaphore=new Semaphore(0);
		private final AtomicInteger counter=new AtomicInteger(0);
		private Selector selector;

		Coordinator(Selector selector){
			this.selector=selector;
		}

		@Override
		public void run(){
			InternalCmd cmd;
			SocketChannel returnedChannel;
			for(;;){
				int count;
				try{
					count=selector.select();
				}catch(IOException e){
					log.w(e);
					break;
				}
				while((returnedChannel=returnQueue.poll())!=null){
					try{
						counter.decrementAndGet();
						returnedChannel.configureBlocking(false);
						returnedChannel.register(selector,SelectionKey.OP_READ);
					}catch(IOException e){
						log.w(e);
					}
				}
				if(count!=0){
					Iterator<SelectionKey> it=selector.selectedKeys().iterator();
					while(it.hasNext()){
						SelectionKey key=it.next();
						it.remove();
						if(key.isAcceptable()){
							ServerSocketChannel ssc=(ServerSocketChannel)key.channel();
							SocketChannel channel;
							try{
								channel=ssc.accept();
								channel.configureBlocking(false);
								channel.register(selector,SelectionKey.OP_READ);
							}catch(IOException e){
								log.w(e);
							}
						}else if(key.isReadable()){
							SocketChannel channel=(SocketChannel)key.channel();
							try{
//								System.out.println("Key: "+key.isValid()+" Block: "+channel.isBlocking());
								key.cancel();
								channel.configureBlocking(true);
							}catch(IOException e){
								log.w(e);
								continue;
							}
//							key.cancel();
							counter.incrementAndGet();
							Worker worker=new Worker(channel,semaphore);
							pool.execute(worker);
						}else{
							log.w("Undefined key state: "+key);
						}
					}
				}
				while((cmd=cmdQueue.poll())!=null){
					InternalCmd.CMD c=cmd.getCMD();
					if(c==InternalCmd.CMD.EXIT){
						for(;;){
							try{
								semaphore.acquire(counter.get());
								break;
							}catch(InterruptedException e){
								log.d(e);
							}
						}
						exitQueue.add(new InternalCmd(InternalCmd.CMD.OK));
						return;
					}
					//  todo more control here
				}
			}
		}
	}

	class Worker implements Runnable{
		private SocketChannel socketChannel;
		private Semaphore semaphore;

		Worker(SocketChannel socketChannel,Semaphore semaphore){
			this.socketChannel=socketChannel;
			this.semaphore=semaphore;
		}

		@Override
		public void run(){
			try{
				process(socketChannel);
			}catch(IOException e){
				log.w(e);
				try{
					socketChannel.close();
				}catch(IOException ignored){}
			}finally{
				semaphore.release();
				returnQueue.add(socketChannel);
				selector.wakeup();
			}
		}
	}
}
