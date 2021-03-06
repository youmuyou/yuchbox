/**
 *  Dear developer:
 *  
 *   If you want to modify this file of project and re-publish this please visit:
 *  
 *     http://code.google.com/p/yuchberry/wiki/Project_files_header
 *     
 *   to check your responsibility and my humble proposal. Thanks!
 *   
 *  -- 
 *  Yuchs' Developer    
 *  
 *  
 *  
 *  
 *  尊敬的开发者：
 *   
 *    如果你想要修改这个项目中的文件，同时重新发布项目程序，请访问一下：
 *    
 *      http://code.google.com/p/yuchberry/wiki/Project_files_header
 *      
 *    了解你的责任，还有我卑微的建议。 谢谢！
 *   
 *  -- 
 *  语盒开发者
 *  
 */
package com.yuchting.yuchdroid.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Vector;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class sendReceive{
	
	public static interface IStoreUpDownloadByte{
		/**
		 * callback and store the upload and download byte 
		 * 
		 * @param _uploadByte
		 * @param _downloadByte
		 */
		public void store(long _uploadByte,long _downloadByte);
		
		/**
		 * get the push interval (pulse cycle)
		 * @return push interval
		 */
		public int getPushInterval();
		
		/**
		 * debug logout
		 * @param _log
		 */
		public void logOut(String _log);
		
		/**
		 * pulse called to do something such as clear history mail and so on
		 */
		public void pulse();
		
	}
	
	public static String TAG = sendReceive.class.getName();
	static final int		fsm_packageHeadLength 	= 4;
	static final byte[] 	fsm_keepliveMsg 		= {1,0,0,0,msg_head.msgKeepLive};
	
	private Selector	m_selector				= null;
	private SocketChannel m_socketChn			= null;
			
	private Vector<byte[]>		m_unsendedPackage 		= new Vector<byte[]>();
	private Vector<byte[]>		m_unprocessedPackage 	= new Vector<byte[]>();
	
	
	boolean			m_closed				= false;
	
	long				m_uploadByte			= 0;
	long				m_downloadByte			= 0;
		
	int					m_storeByteTimer		= 0;
	
	IStoreUpDownloadByte	m_storeInterface	= null;
	
	private final static String FILTER_PULSE	= TAG+"_FP";
	private PendingIntent	m_pulseAlarm = null;
	private BroadcastReceiver m_pulseAlarmRecv = null;
	private Context	m_context	= null;
	private int			m_formerPulseInterval = 0;
	
	private Vector<ByteBuffer>					m_sendBufferVect = new Vector<ByteBuffer>();
	
	// android client need server send keeplive back
	//
	private boolean		m_keeplive			= true;
	private boolean 		m_keepliveClose		= false;
			
	public sendReceive(Context _ctx,Selector _selector,SocketChannel _chn,boolean _ssl,
					IStoreUpDownloadByte _callback)throws Exception{

		if(_ssl){
			throw new IllegalArgumentException(TAG + " Current YuchsBox can't support !");
		}
		
		m_context	= _ctx;
		m_selector	= _selector;
		m_socketChn = _chn;
						
		m_storeInterface = _callback;
		
		// register the selector 
		//
		m_socketChn.register(m_selector,SelectionKey.OP_WRITE);
		
		startAlarmForPulse();
	}
					
	public void RegisterStoreUpDownloadByte(IStoreUpDownloadByte _interface){
		m_storeInterface = _interface;
	}
	
	//! send buffer
	public void SendBufferToSvr(byte[] _write,boolean _sendImm)throws Exception{
		
		m_unsendedPackage.addElement(_write);
		
		if(_sendImm){
			SendBufferToSvr_imple(PrepareOutputData());
		}

		m_selector.wakeup();			
	}
	
	public void StoreUpDownloadByteImm(boolean _force){
		if(m_storeInterface != null){
			if(m_storeByteTimer++ > 5 || _force){
				m_storeByteTimer = 0;
				m_storeInterface.store(m_uploadByte,m_downloadByte);
				m_uploadByte = 0;
				m_downloadByte = 0;
			}			
		}
	}
	
	public void CloseSendReceive(){
		
		if(m_closed == false){
			
			StoreUpDownloadByteImm(true);
			
			m_closed = true;
			
			m_unsendedPackage.removeAllElements();
			m_unprocessedPackage.removeAllElements();
			
			try{
				m_selector.wakeup();
			}catch(Exception e){}
		}
		
		stopAlarmForPulse();
	}
	
	private void startAlarmForPulse(){
		if(m_pulseAlarm == null){
			
			m_formerPulseInterval = m_storeInterface.getPushInterval();
			
			AlarmManager t_msg = (AlarmManager)m_context.getSystemService(Context.ALARM_SERVICE);
			Intent notificationIntent = new Intent(FILTER_PULSE);
			m_pulseAlarm = PendingIntent.getBroadcast(m_context, 0, notificationIntent,0);
			t_msg.setRepeating(AlarmManager.RTC_WAKEUP, 
							System.currentTimeMillis() + m_formerPulseInterval, 
							m_formerPulseInterval, /* a bit less*/ 
							m_pulseAlarm);
			
			if(m_pulseAlarmRecv == null){
				m_pulseAlarmRecv = new BroadcastReceiver() {
					
					@Override
					public void onReceive(Context context, Intent intent){

						if(!m_keeplive){
							m_keepliveClose = true;
						}else{
							if(m_formerPulseInterval != m_storeInterface.getPushInterval()){
								m_formerPulseInterval = m_storeInterface.getPushInterval();
								stopAlarmForPulse();
								startAlarmForPulse();
							}
						}
						
						m_storeInterface.pulse();
						
						// just wake up selector to let the NIO socket send a pulse
						//
						try{						
							m_selector.wakeup();
						}catch(Exception e){}
					}
				};
			}
			
			m_context.registerReceiver(m_pulseAlarmRecv, new IntentFilter(FILTER_PULSE));
		}
	}
	
	private void stopAlarmForPulse(){
		if(m_pulseAlarm != null){
			m_pulseAlarm.cancel();
			m_pulseAlarm = null;
			
			m_context.unregisterReceiver(m_pulseAlarmRecv);
		}
	}
		
	private byte[] PrepareOutputData()throws Exception{
		
		if(m_unsendedPackage.isEmpty()){
			return null;
		}
		
		ByteArrayOutputStream t_stream = new ByteArrayOutputStream();
	
		synchronized (m_unsendedPackage) {
			for(int i = 0;i < m_unsendedPackage.size();i++){
				byte[] t_package = (byte[])m_unsendedPackage.elementAt(i);	
				
				WriteInt(t_stream, t_package.length);
							
				t_stream.write(t_package);
			}
			
			m_unsendedPackage.removeAllElements();
		}	
		
		return t_stream.toByteArray();
	}
	
	private byte[] readData(int _len)throws Exception{
		
		ByteBuffer t_retBuf = ByteBuffer.allocate(_len);
		
		int t_selectkey = 0;
		while(true){
			
			t_selectkey = m_selector.select();
			
			if(m_closed){
				m_socketChn.close();
				m_selector.close();				
				throw new Exception(TAG + " Client own closed!");
			}
						
			if(m_keepliveClose){
				throw new Exception(TAG + " keeplive can't sendback!");
			}
			
			if(!m_unsendedPackage.isEmpty()){
				SendBufferToSvr_imple(PrepareOutputData());				
				m_socketChn.keyFor(m_selector).interestOps(SelectionKey.OP_WRITE);
				
				if(t_selectkey == 0){
					// the next select will hold the write op
					//
					continue;
				}
			}
				
			if(t_selectkey != 0){
				
				Iterator<SelectionKey> it = m_selector.selectedKeys().iterator();
				while(it.hasNext()){
					SelectionKey key = it.next();
					it.remove();
					
					if(key.isValid()){
												
						if(key.isReadable()){
							
							SocketChannel t_chn = (SocketChannel)key.channel();						
														
							if(t_chn.read(t_retBuf) == -1){
								throw new Exception(TAG + " Client read -1 to closed!");
							}				
							
							if(t_retBuf.hasRemaining()){
								continue;
							}
							
							m_keeplive = true;
							
							t_retBuf.flip();
							return t_retBuf.array();
						}else if(key.isWritable()){	
							sendDataByChn_impl(key);
						}else{
							m_storeInterface.logOut("valid key (!isWritable && !isReadable)");
						}
					}
				}
				
			}else{
				
				if(!m_socketChn.isConnected()){
					throw new Exception(TAG + " Socket chn is not connected!");
				}else{
					// send the keeplive message
					//
					SendBufferToSvr_imple(fsm_keepliveMsg);
					m_socketChn.keyFor(m_selector).interestOps(SelectionKey.OP_WRITE);
					m_keeplive = false;
				}
			}
			
			
		}
	}
	
	private void sendDataByChn_impl(SelectionKey _key)throws Exception{
		
	    SocketChannel t_socketChn = (SocketChannel)_key.channel();

	    synchronized(m_sendBufferVect) {
	    	
	    	while(!m_sendBufferVect.isEmpty()){
	    		ByteBuffer t_sendBuffer = m_sendBufferVect.get(0);
	    		t_socketChn.write(t_sendBuffer);
	    		if(t_sendBuffer.remaining() > 0){
	    			break;
	    		}
	    		
	    		m_sendBufferVect.remove(0);
	    	}
	    	
	    	if(m_sendBufferVect.isEmpty()){
	    		_key.interestOps(SelectionKey.OP_READ);
	    	}
	    }
		  
	}
	
	private byte[] readDataByChn_impl()throws Exception{
		
		InputStream in = new ByteArrayInputStream(readData(4));
		try{
			int t_len = ReadInt(in);
			if(t_len < 0){
				throw new Exception("socket ReadInt failed");
			}
			
			int t_ziplen;
			int t_orglen;
			
			if(t_len == 0){
				
				InputStream lin = new ByteArrayInputStream(readData(8));
				try{

					// read the long length header
					//
					t_orglen = ReadInt(lin);
					t_ziplen = ReadInt(lin);
					
					if(t_orglen == -1 || t_ziplen == -1){
						throw new Exception("orglen ReadInt failed");
					}
					
					m_downloadByte += 8;
					
				}finally{
					
					lin.close();
					lin = null;
				}
				
			}else{			
				// read the normal length header
				//
				t_ziplen = t_len & 0x0000ffff;
				t_orglen = t_len >>> 16;
			}
			
							
			byte[] t_orgdata;
			
			if(t_ziplen == 0){
			
				t_orgdata = readData(t_orglen);
																			
				synchronized (this) {
					// 20 is TCP pack head length
					m_downloadByte += t_orglen + 4 + 20;
				}
				
			}else{
							
				synchronized (this) {
					// 20 is TCP pack head length
					m_downloadByte += t_ziplen + 4 + 20;
				}
				
				t_orgdata = new byte[t_orglen];
				
				InputStream zin = new ByteArrayInputStream(readData(t_ziplen));
				try{
					GZIPInputStream zi = new GZIPInputStream(zin);	
					try{
						ForceReadByte(zi,t_orgdata,t_orglen);
						zi.close();
					}finally{
						zi.close();
						zi = null;
					}				
				}finally{
					zin.close();
					zin = null;
				}			
			}
			
			return ParsePackage(t_orgdata);	
			
		}finally{
			in.close();
			in = null;
		}
		
	}

	//! send buffer implement
	private  void SendBufferToSvr_imple(byte[] _write)throws Exception{
		
		if(_write == null){
			return;
		}
				
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		ByteArrayOutputStream zos = new ByteArrayOutputStream();
		
		GZIPOutputStream zo = new GZIPOutputStream(zos,6);
		zo.write(_write);
		zo.close();	
		
		byte[] t_zipData = zos.toByteArray();
		
		if(t_zipData.length > _write.length){
			
			// if the ZIP data is large than original length
			// NOT convert
			//
			if(_write.length >= 65535){
				WriteInt(os,0); // big read flag
				
				WriteInt(os,_write.length);
				WriteInt(os,0);
				
				m_uploadByte += 8;
			}else{
				WriteInt(os,(_write.length << 16) & 0xffff0000);
			}
			
			os.write(_write);
			os.flush();
			
			synchronized (this) {
				// 20 is TCP pack head length			
				m_uploadByte += _write.length + 4 + 20;
			}
	
		}else{
						
			if(_write.length >= 65535){
				WriteInt(os,0); // big read flag
				
				WriteInt(os,_write.length);
				WriteInt(os,t_zipData.length);
				
				m_uploadByte += 8;
			}else{
				WriteInt(os,((_write.length << 16) & 0xffff0000) | t_zipData.length);
			}
			
			os.write(t_zipData);
			os.flush();
			
			synchronized (this) {
				// 20 is TCP pack head length
				m_uploadByte += t_zipData.length + 4 + 20;
			}
		}
		
		// send the buffer to blocking SocketChannel 
		//
		byte[] t_finalSend = os.toByteArray();
		
		ByteBuffer t_buffer = ByteBuffer.allocate(t_finalSend.length);
		t_buffer.put(t_finalSend);
		t_buffer.flip();
		
		m_sendBufferVect.add(t_buffer);
	}
	
	
	//! recv buffer
	public byte[] RecvBufferFromSvr()throws Exception{
		
		if(!m_unprocessedPackage.isEmpty()){
			byte[] t_ret = (byte[])m_unprocessedPackage.elementAt(0);
			m_unprocessedPackage.removeElementAt(0);
			
			return t_ret;
		}
			
		return readDataByChn_impl();
	}
	
	private byte[] ParsePackage(byte[] _wholePackage)throws Exception{
		
		ByteArrayInputStream t_packagein = new ByteArrayInputStream(_wholePackage);
		int t_len = ReadInt(t_packagein);
					
		byte[] t_ret = new byte[t_len];
		t_packagein.read(t_ret,0,t_len);
		
		t_len += 4;
		
		while(t_len < _wholePackage.length){
			
			final int t_packageLen = ReadInt(t_packagein); 
			
			byte[] t_package = new byte[t_packageLen];
			
			t_packagein.read(t_package,0,t_packageLen);
			t_len += t_packageLen + 4;
			
			m_unprocessedPackage.addElement(t_package);			
		}		
		
		return t_ret;		
	}
	// static function to input and output integer
	//
	static public void WriteStringVector(OutputStream _stream,Vector<String> _vect)throws Exception{
		
		final int t_size = _vect.size();
		WriteInt(_stream,t_size);
		
		for(int i = 0;i < t_size;i++){
			WriteString(_stream,(String)_vect.elementAt(i));
		}
	}
	
	static public void WriteString(OutputStream _stream,String _string)throws Exception{
		if(_string == null){
			_string = "";
		}
		
		byte[] t_strByte;
		
		try{
			// if the UTF-8 decode sytem is NOT present in current system
			// will throw the exception
			//
			t_strByte = _string.getBytes("UTF-8");
		}catch(Exception e){
			t_strByte = _string.getBytes();
		}
		
		WriteInt(_stream,t_strByte.length);
		if(t_strByte.length != 0){
			_stream.write(t_strByte);
		}
	}
	
	static public void WriteDouble(OutputStream _stream,double _val)throws Exception{
		if(_val == 0){
			WriteInt(_stream,0);
		}else{
			String t_valString = Double.toString(_val);
			WriteString(_stream,t_valString);
		}		
	}
	
	static public void WriteFloat(OutputStream _stream,float _val)throws Exception{
		if(_val == 0){
			WriteInt(_stream,0);
		}else{
			String t_valString = Float.toString(_val);
			WriteString(_stream,t_valString);
		}
	}
	
	static public double ReadDouble(InputStream _stream)throws Exception{
		String t_valString = ReadString(_stream);
		if(t_valString.length() == 0){
			return 0;
		}else{
			return Double.valueOf(t_valString).doubleValue();			
		}
		
	}
	
	static public float ReadFloat(InputStream _stream)throws Exception{
		String t_valString = ReadString(_stream);
		if(t_valString.length() == 0){
			return 0;
		}else{
			return Float.valueOf(t_valString).floatValue();
		}
	}
	
	static public void WriteBoolean(OutputStream _stream,boolean _val)throws Exception{
		_stream.write(_val?1:0);
	}
	
	static public boolean ReadBoolean(InputStream _stream)throws Exception{
		
		int t_counter = 0;
		int t_val = 0;
		while(true){
			
			t_val = _stream.read();				
			
			if(t_val == -1){
				
				if(t_counter++ >= 20){
					return false;
				}
				
				// first sleep 
				//
				Thread.sleep(20);
				continue;
				
			}else{
				break;
			}
		}			

		return t_val == 1;		
	}
		
	static public void ReadStringVector(InputStream _stream,Vector<String> _vect)throws Exception{
		
		_vect.removeAllElements();
		
		final int t_size = ReadInt(_stream);
				
		for(int i = 0;i < t_size;i++){
			_vect.addElement(ReadString(_stream));
		}
	}
	
	static public String ReadString(InputStream _stream)throws Exception{
		
		final int len = ReadInt(_stream);
		
		if(len != 0){
			byte[] t_buffer = new byte[len];
			
			ForceReadByte(_stream,t_buffer,len);

			try{
				// if the UTF-8 decode sytem is NOT present in current system
				// will throw the exception
				//
				return new String(t_buffer,"UTF-8");
			}catch(Exception e){}
			
			return new String(t_buffer);
			
		}
		
		return "";
		
	}
	
	static public int ReadInt(InputStream _stream)throws Exception{
		
		int[] t_byte = {0,0,0,0};
	
		int t_counter = 0;
		
		for(int i = 0;i < t_byte.length;i++){
			
			while(true){
				
				t_byte[i] = _stream.read();				
				
				if(t_byte[i] == -1){
					
					if(t_counter++ >= 20){
						return -1;
					}
					
					// first sleep 
					//
					Thread.sleep(20);					
					continue;
					
				}else{
					break;
				}
			}			
						
		}
		
		return t_byte[0] | (t_byte[1] << 8) | (t_byte[2]  << 16) | (t_byte[3] << 24);
			
	}
	
	static public long ReadLong(InputStream _stream)throws Exception{
		final int t_timeLow = sendReceive.ReadInt(_stream);
		final long t_timeHigh = sendReceive.ReadInt(_stream);
				
		if(t_timeLow >= 0){
			return ((t_timeHigh << 32) | (long)(t_timeLow));
		}else{
			return ((t_timeHigh << 32) | (((long)(t_timeLow & 0x7fffffff)) | 0x80000000L));
		}
	}
		
	static public void WriteLong(OutputStream _stream,long _val)throws Exception{		
		sendReceive.WriteInt(_stream,(int)_val);
		sendReceive.WriteInt(_stream,(int)(_val >>> 32));
	}
	
	static public void WriteInt(OutputStream _stream,int _val)throws Exception{
		_stream.write(_val);
		_stream.write(_val >>> 8 );
		_stream.write(_val >>> 16);
		_stream.write(_val >>> 24);
	}
	
	static public void ForceReadByte(InputStream _stream,byte[] _buffer,int _readLen)throws Exception{
		int t_readIndex = 0;
		int t_counter = 0;
		
		while(_readLen > t_readIndex){
			final int t_c = _stream.read(_buffer,t_readIndex,_readLen - t_readIndex);
			if(t_c > 0){
				t_readIndex += t_c;
			}else{
				
				if(++t_counter > 20){
					throw new Exception("FroceReadByte failed " + _readLen );
				}
				
				// first sleep 
				//
				Thread.sleep(20);	
			}		
		}
	}
	
}

