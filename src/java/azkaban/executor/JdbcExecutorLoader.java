package azkaban.executor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import azkaban.executor.ExecutableFlow.Status;
import azkaban.utils.DataSourceUtils;
import azkaban.utils.FileIOUtils;
import azkaban.utils.FileIOUtils.LogData;
import azkaban.utils.GZIPUtils;
import azkaban.utils.JSONUtils;
import azkaban.utils.Pair;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;

public class JdbcExecutorLoader implements ExecutorLoader {
	private static final Logger logger = Logger.getLogger(JdbcExecutorLoader.class);
	
	/**
	 * Used for when we store text data. Plain uses UTF8 encoding.
	 */
	public static enum EncodingType {
		PLAIN(1), GZIP(2);

		private int numVal;

		EncodingType(int numVal) {
			this.numVal = numVal;
		}

		public int getNumVal() {
			return numVal;
		}

		public static EncodingType fromInteger(int x) {
			switch (x) {
			case 1:
				return PLAIN;
			case 2:
				return GZIP;
			default:
				return PLAIN;
			}
		}
	}
	
	private DataSource dataSource;
	private EncodingType defaultEncodingType = EncodingType.GZIP;
	
	public JdbcExecutorLoader(Props props) {
		String databaseType = props.getString("database.type");

		if (databaseType.equals("mysql")) {
			int port = props.getInt("mysql.port");
			String host = props.getString("mysql.host");
			String database = props.getString("mysql.database");
			String user = props.getString("mysql.user");
			String password = props.getString("mysql.password");
			int numConnections = props.getInt("mysql.numconnections");
			
			dataSource = DataSourceUtils.getMySQLDataSource(host, port, database, user, password, numConnections);
		}
	}

	public EncodingType getDefaultEncodingType() {
		return defaultEncodingType;
	}

	public void setDefaultEncodingType(EncodingType defaultEncodingType) {
		this.defaultEncodingType = defaultEncodingType;
	}
	
	@Override
	public synchronized void uploadExecutableFlow(ExecutableFlow flow) throws ExecutorManagerException {
		Connection connection = getConnection();
		try {
			uploadExecutableFlow(connection, flow, defaultEncodingType);
		} catch (IOException e) {
			throw new ExecutorManagerException("Error uploading flow", e);
		}
		finally {
			DbUtils.closeQuietly(connection);
		}
	}
	
	private synchronized void uploadExecutableFlow(Connection connection, ExecutableFlow flow, EncodingType encType) throws ExecutorManagerException, IOException {
		final String INSERT_EXECUTABLE_FLOW = "INSERT INTO execution_flows (project_id, flow_id, version, status, submit_time, submit_user, update_time) values (?,?,?,?,?,?,?)";
		QueryRunner runner = new QueryRunner();
		long submitTime = System.currentTimeMillis();

		long id;
		try {
			flow.setStatus(ExecutableFlow.Status.PREPARING);
			runner.update(connection, INSERT_EXECUTABLE_FLOW, flow.getProjectId(), flow.getFlowId(), flow.getVersion(), ExecutableFlow.Status.PREPARING.getNumVal(), submitTime, flow.getSubmitUser(), submitTime);
			connection.commit();
			id = runner.query(connection, LastInsertID.LAST_INSERT_ID, new LastInsertID());

			if (id == -1l) {
				throw new ExecutorManagerException("Execution id is not properly created.");
			}
			logger.info("Flow given " + flow.getFlowId() + " given id " + id);
			flow.setExecutionId((int)id);
			
			updateExecutableFlow(connection, flow, encType);
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error creating execution.", e);
		}
	}
	
	@Override
	public void updateExecutableFlow(ExecutableFlow flow) throws ExecutorManagerException {
		Connection connection = this.getConnection();
		
		try {
			updateExecutableFlow(connection, flow, defaultEncodingType);
		}
		finally {
			DbUtils.closeQuietly(connection);
		}
	} 
	
	private void updateExecutableFlow(Connection connection, ExecutableFlow flow, EncodingType encType) throws ExecutorManagerException {
		final String UPDATE_EXECUTABLE_FLOW_DATA = "UPDATE execution_flows SET status=?,update_time=?,start_time=?,end_time=?,enc_type=?,flow_data=? WHERE exec_id=?";
		QueryRunner runner = new QueryRunner();
		
		String json = JSONUtils.toJSON(flow.toObject());
		byte[] data = null;
		try {
			byte[] stringData = json.getBytes("UTF-8");
			data = stringData;
	
			if (encType == EncodingType.GZIP) {
				data = GZIPUtils.gzipBytes(stringData);
			}
			logger.debug("NumChars: " + json.length() + " UTF-8:" + stringData.length + " Gzip:"+ data.length);
		}
		catch (IOException e) {
			throw new ExecutorManagerException("Error encoding the execution flow.");
		}
		
		try {
			runner.update(connection, UPDATE_EXECUTABLE_FLOW_DATA, flow.getStatus().getNumVal(), flow.getUpdateTime(), flow.getStartTime(), flow.getEndTime(), encType.getNumVal(), data, flow.getExecutionId());
			connection.commit();
		}
		catch(SQLException e) {
			throw new ExecutorManagerException("Error updating flow.", e);
		}
	}
	
	@Override
	public ExecutableFlow fetchExecutableFlow(int id) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		FetchExecutableFlows flowHandler = new FetchExecutableFlows();

		try {
			List<ExecutableFlow> properties = runner.query(FetchExecutableFlows.FETCH_EXECUTABLE_FLOW, flowHandler, id);
			return properties.get(0);
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching flow id " + id, e);
		}
	}
	
	@Override
	public Map<Integer, Pair<ExecutionReference, ExecutableFlow>> fetchActiveFlows() throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		FetchActiveExecutableFlows flowHandler = new FetchActiveExecutableFlows();

		try {
			Map<Integer, Pair<ExecutionReference, ExecutableFlow>> properties = runner.query(FetchActiveExecutableFlows.FETCH_ACTIVE_EXECUTABLE_FLOW, flowHandler);
			return properties;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching active flows", e);
		}
	}
	
	@Override
	public int fetchNumExecutableFlows() throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		
		IntHandler intHandler = new IntHandler();
		try {
			int count = runner.query(IntHandler.NUM_EXECUTIONS, intHandler);
			return count;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching num executions", e);
		}
	}
	
	@Override
	public int fetchNumExecutableFlows(int projectId, String flowId) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		
		IntHandler intHandler = new IntHandler();
		try {
			int count = runner.query(IntHandler.NUM_FLOW_EXECUTIONS, intHandler, projectId, flowId);
			return count;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching num executions", e);
		}
	}
	
	@Override
	public int fetchNumExecutableNodes(int projectId, String jobId) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		
		IntHandler intHandler = new IntHandler();
		try {
			int count = runner.query(IntHandler.NUM_JOB_EXECUTIONS, intHandler, projectId, jobId);
			return count;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching num executions", e);
		}
	}
	
	@Override
	public List<ExecutableFlow> fetchFlowHistory(int projectId, String flowId, int skip, int num) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		FetchExecutableFlows flowHandler = new FetchExecutableFlows();

		try {
			List<ExecutableFlow> properties = runner.query(FetchExecutableFlows.FETCH_EXECUTABLE_FLOW_HISTORY, flowHandler, projectId, flowId, skip, num);
			return properties;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching active flows", e);
		}
	}
	
	@Override
	public List<ExecutableFlow> fetchFlowHistory(int skip, int num) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		FetchExecutableFlows flowHandler = new FetchExecutableFlows();
		
		try {
			List<ExecutableFlow> properties = runner.query(FetchExecutableFlows.FETCH_ALL_EXECUTABLE_FLOW_HISTORY, flowHandler, skip, num);
			return properties;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching active flows", e);
		}
	}
	

	@Override
	public List<ExecutableFlow> fetchFlowHistory(String projContain, String flowContains, String userNameContains, int status, long startTime, long endTime, int skip, int num) throws ExecutorManagerException {
		String query = FetchExecutableFlows.FETCH_BASE_EXECUTABLE_FLOW_QUERY;
		ArrayList<Object> params = new ArrayList<Object>();
		
		boolean first = true;
		if (projContain != null) {
			query += " ef JOIN projects p ON ef.project_id = p.id WHERE name LIKE ?";
			params.add('%'+projContain+'%');
			first = false;
		}
		
		if (flowContains != null) {
			if (first) {
				query += " WHERE ";
				first = false;
			}
			else {
				query += " AND ";
			}

			query += " flow_id LIKE ?";
			params.add('%'+flowContains+'%');
		}
		
		if (userNameContains != null) {
			if (first) {
				query += " WHERE ";
				first = false;
			}
			else {
				query += " AND ";
			}
			query += " submit_user LIKE ?";
			params.add('%'+userNameContains+'%');
		}
		
		if (status != 0) {
			if (first) {
				query += " WHERE ";
				first = false;
			}
			else {
				query += " AND ";
			}
			query += " status = ?";
			params.add(status);
		}
		
		if (startTime > 0) {
			if (first) {
				query += " WHERE ";
				first = false;
			}
			else {
				query += " AND ";
			}
			query += " start_time > ?";
			params.add(startTime);
		}
		
		if (endTime > 0) {
			if (first) {
				query += " WHERE ";
				first = false;
			}
			else {
				query += " AND "; 
			}
			query += " end_time < ?";
			params.add(endTime);
		}
		
		if (skip > -1 && num > 0) {
			query += "  ORDER BY exec_id DESC LIMIT ?, ?";
			params.add(skip);
			params.add(num);
		}
		
		QueryRunner runner = new QueryRunner(dataSource);
		FetchExecutableFlows flowHandler = new FetchExecutableFlows();

		try {
			List<ExecutableFlow> properties = runner.query(query, flowHandler, params.toArray());
			return properties;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching active flows", e);
		}
	}
	
	@Override
	public void addActiveExecutableReference(ExecutionReference reference) throws ExecutorManagerException {
		final String INSERT = "INSERT INTO active_executing_flows (exec_id, host, port, update_time) values (?,?,?,?)";
		QueryRunner runner = new QueryRunner(dataSource);
		
		try {
			runner.update(INSERT, reference.getExecId(), reference.getHost(), reference.getPort(), reference.getUpdateTime());
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error updating active flow reference " + reference.getExecId(), e);
		}
	}
	
	@Override
	public void removeActiveExecutableReference(int execid) throws ExecutorManagerException {
		final String DELETE = "DELETE FROM active_executing_flows WHERE exec_id=?";
		
		QueryRunner runner = new QueryRunner(dataSource);
		try {
			runner.update(DELETE, execid);
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error deleting active flow reference " + execid, e);
		}
	}
	
	@Override
	public boolean updateExecutableReference(int execId, long updateTime) throws ExecutorManagerException {
		final String DELETE = "UPDATE active_executing_flows set update_time=? WHERE exec_id=?";
		
		QueryRunner runner = new QueryRunner(dataSource);
		int updateNum = 0;
		try {
			updateNum = runner.update(DELETE, updateTime, execId);
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error deleting active flow reference " + execId, e);
		}
		
		// Should be 1.
		return updateNum > 0;
	}

	@Override
	public void uploadExecutableNode(ExecutableNode node, Props inputProps) throws ExecutorManagerException {
		final String INSERT_EXECUTION_NODE = "INSERT INTO execution_jobs (exec_id, project_id, version, flow_id, job_id, start_time, end_time, status, input_params) VALUES (?,?,?,?,?,?,?,?,?)";
		
		byte[] inputParam = null;
		if (inputProps != null) {
			try {
				String jsonString = JSONUtils.toJSON(PropsUtils.toHierarchicalMap(inputProps));
				inputParam = GZIPUtils.gzipString(jsonString, "UTF-8");
			} catch (IOException e) {
				throw new ExecutorManagerException("Error encoding input params");
			}
		}
		
		ExecutableFlow flow = node.getFlow();
		QueryRunner runner = new QueryRunner(dataSource);
		try {
			runner.update(
					INSERT_EXECUTION_NODE, 
					flow.getExecutionId(), 
					flow.getProjectId(), 
					flow.getVersion(), 
					flow.getFlowId(), 
					node.getJobId(),
					node.getStartTime(),
					node.getEndTime(), 
					node.getStatus().getNumVal(),
					inputParam);
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error writing job " + node.getJobId(), e);
		}
	}
	
	@Override
	public void updateExecutableNode(ExecutableNode node, Props outputProps) throws ExecutorManagerException {
		final String UPSERT_EXECUTION_NODE = "UPDATE execution_jobs SET start_time=?, end_time=?, status=?, output_params=? WHERE exec_id=? AND job_id=?";
		
		byte[] outputParam = null;
		if (outputProps != null) {
			try {
				String jsonString = JSONUtils.toJSON(PropsUtils.toHierarchicalMap(outputProps));
				outputParam = GZIPUtils.gzipString(jsonString, "UTF-8");
			} catch (IOException e) {
				throw new ExecutorManagerException("Error encoding input params");
			}
		}
		
		QueryRunner runner = new QueryRunner(dataSource);
		try {
			runner.update(
					UPSERT_EXECUTION_NODE, 
					node.getStartTime(), 
					node.getEndTime(), 
					node.getStatus().getNumVal(), 
					outputParam,
					node.getExecutionId(),
					node.getJobId());
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error updating job " + node.getJobId(), e);
		}
	}
	
	@Override
	public ExecutableJobInfo fetchJobInfo(int execId, String jobId) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		
		try {
			List<ExecutableJobInfo> info = runner.query(FetchExecutableJobHandler.FETCH_EXECUTABLE_NODE, new FetchExecutableJobHandler(), execId, jobId);
			if (info == null || info.isEmpty()) {
				return null;
			}
			
			return info.get(0);
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error querying job info " + jobId, e);
		}
	}
	
	@Override
	public List<ExecutableJobInfo> fetchJobHistory(int projectId, String jobId, int skip, int size) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);
		
		try {
			List<ExecutableJobInfo> info = runner.query(FetchExecutableJobHandler.FETCH_PROJECT_EXECUTABLE_NODE, new FetchExecutableJobHandler(), projectId, jobId, skip, size);
			if (info == null || info.isEmpty()) {
				return null;
			}
			
			return info;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error querying job info " + jobId, e);
		}
	}
	
	@Override
	public LogData fetchLogs(int execId, String name, int startByte, int length) throws ExecutorManagerException {
		QueryRunner runner = new QueryRunner(dataSource);

		FetchLogsHandler handler = new FetchLogsHandler(startByte, length + startByte);
		
		try {
			LogData result = runner.query(FetchLogsHandler.FETCH_LOGS, handler, execId, name, startByte, startByte + length);
			return result;
		} catch (SQLException e) {
			throw new ExecutorManagerException("Error fetching logs " + execId + " : " + name, e);
		}
	}
	
	@Override
	public void uploadLogFile(int execId, String name, File ... files) throws ExecutorManagerException {
		Connection connection = getConnection();
		try {
			uploadLogFile(connection, execId, name, files, defaultEncodingType);
			connection.commit();
		}
		catch (SQLException e) {
			throw new ExecutorManagerException("Error committing log", e);
		}
		catch (IOException e) {
			throw new ExecutorManagerException("Error committing log", e);
		}
		finally {
			DbUtils.closeQuietly(connection);
		}
	}
	
	private void uploadLogFile(Connection connection, int execId, String name, File[] files, EncodingType encType) throws ExecutorManagerException, IOException {
		// 50K buffer... if logs are greater than this, we chunk.
		// However, we better prevent large log files from being uploaded somehow
		byte[] buffer = new byte[50*1024];
		int pos = 0;
		int length = buffer.length;
		int startByte = 0;
		BufferedInputStream bufferedStream = null;
		try {
			for (int i = 0; i < files.length; ++i) {
				File file = files[i];
				
				bufferedStream = new BufferedInputStream(new FileInputStream(file));
				int size = bufferedStream.read(buffer, pos, length);
				while (size >= 0) {
					if (pos + size == buffer.length) {
						// Flush here.
						uploadLogPart(connection, execId, name, startByte, startByte + buffer.length, encType, buffer, buffer.length);
						
						pos = 0;
						length = buffer.length;
						startByte += buffer.length;
					}
					else {
						// Usually end of file.
						pos += size;
						length = buffer.length - pos;
					}
					size = bufferedStream.read(buffer, pos, length);
				}
			}
			
			// Final commit of buffer.
			if (pos > 0) {
				uploadLogPart(connection, execId, name, startByte, startByte + pos, encType, buffer, pos);
			}
		}
		catch (SQLException e) {
			throw new ExecutorManagerException("Error writing log part.", e);
		}
		catch (IOException e) {
			throw new ExecutorManagerException("Error chunking", e);
		}
		finally {
			IOUtils.closeQuietly(bufferedStream);
		}

	}
	
	private void uploadLogPart(Connection connection, int execId, String name, int startByte, int endByte, EncodingType encType, byte[] buffer, int length) throws SQLException, IOException {
		final String INSERT_EXECUTION_LOGS = "INSERT INTO execution_logs (exec_id, name, enc_type, start_byte, end_byte, log) VALUES (?,?,?,?,?,?)";
		QueryRunner runner = new QueryRunner();
		
		byte[] buf = buffer;
		if (encType == EncodingType.GZIP) {
			buf = GZIPUtils.gzipBytes(buf, 0, length);
		}
		else if (length < buf.length) {
			buf = Arrays.copyOf(buffer, length);
		}
		
		runner.update(connection, INSERT_EXECUTION_LOGS, execId, name, encType.getNumVal(), startByte, startByte + length, buf);
	}
	
	private Connection getConnection() throws ExecutorManagerException {
		Connection connection = null;
		try {
			connection = dataSource.getConnection();
			connection.setAutoCommit(false);
		} catch (Exception e) {
			DbUtils.closeQuietly(connection);
			throw new ExecutorManagerException("Error getting DB connection.", e);
		}
		
		return connection;
	}
	
	private static class LastInsertID implements ResultSetHandler<Long> {
		private static String LAST_INSERT_ID = "SELECT LAST_INSERT_ID()";
		
		@Override
		public Long handle(ResultSet rs) throws SQLException {
			if (!rs.next()) {
				return -1l;
			}

			long id = rs.getLong(1);
			return id;
		}
		
	}
	
	private static class FetchLogsHandler implements ResultSetHandler<LogData> {
		private static String FETCH_LOGS = "SELECT exec_id, name, enc_type, start_byte, end_byte, log FROM execution_logs WHERE exec_id=? AND name=? AND end_byte > ? AND start_byte <= ? ORDER BY start_byte";

		private int startByte;
		private int endByte;
		
		public FetchLogsHandler(int startByte, int endByte) {
			this.startByte = startByte;
			this.endByte = endByte;
		}
		
		@Override
		public LogData handle(ResultSet rs) throws SQLException {
			if (!rs.next()) {
				return null;
			}
			
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

			do {
				//int execId = rs.getInt(1);
				//String name = rs.getString(2);
				EncodingType encType = EncodingType.fromInteger(rs.getInt(3));
				int startByte = rs.getInt(4);
				int endByte = rs.getInt(5);

				byte[] data = rs.getBytes(6);

				int offset = this.startByte > startByte ? this.startByte - startByte : 0;
				int length = this.endByte < endByte ? this.endByte - startByte - offset: endByte - startByte - offset;
				try {
					byte[] buffer = data;
					if (encType == EncodingType.GZIP) {
						buffer = GZIPUtils.unGzipBytes(data);
					}

					byteStream.write(buffer, offset, length);
				} catch (IOException e) {
					throw new SQLException(e);
				}
			} while (rs.next());

			byte[] buffer = byteStream.toByteArray();
			Pair<Integer,Integer> result = FileIOUtils.getUtf8Range(buffer, 0, buffer.length);
		
			return new LogData(startByte + result.getFirst(), result.getSecond(), new String(buffer, result.getFirst(), result.getSecond()));
		}
	}
	
	private static class FetchExecutableJobHandler implements ResultSetHandler<List<ExecutableJobInfo>> {
		private static String FETCH_EXECUTABLE_NODE = "SELECT exec_id, project_id, version, flow_id, job_id, start_time, end_time, status FROM execution_jobs WHERE exec_id=? AND job_id=?";
		private static String FETCH_PROJECT_EXECUTABLE_NODE = "SELECT exec_id, project_id, version, flow_id, job_id, start_time, end_time, status FROM execution_jobs WHERE project_id=? AND job_id=? ORDER BY exec_id DESC LIMIT ?, ? ";

		@Override
		public List<ExecutableJobInfo> handle(ResultSet rs) throws SQLException {
			if (!rs.next()) {
				return Collections.<ExecutableJobInfo>emptyList();
			}
			
			List<ExecutableJobInfo> execNodes = new ArrayList<ExecutableJobInfo>();
			do {
				int execId = rs.getInt(1);
				int projectId = rs.getInt(2);
				int version = rs.getInt(3);
				String flowId = rs.getString(4);
				String jobId = rs.getString(5);
				long startTime = rs.getLong(6);
				long endTime = rs.getLong(7);
				Status status = Status.fromInteger(rs.getInt(8));
				
				ExecutableJobInfo info = new ExecutableJobInfo(execId, projectId, version, flowId, jobId, startTime, endTime, status);
				execNodes.add(info);
			} while (rs.next());

			return execNodes;
		}
	}
	

	private static class FetchActiveExecutableFlows implements ResultSetHandler<Map<Integer, Pair<ExecutionReference,ExecutableFlow>>> {
		private static String FETCH_ACTIVE_EXECUTABLE_FLOW = "SELECT ex.exec_id exec_id, ex.enc_type enc_type, ex.flow_data flow_data, ax.host host, ax.port port, ax.update_time axUpdateTime FROM execution_flows ex INNER JOIN active_executing_flows ax ON ex.exec_id = ax.exec_id";
		
		@Override
		public Map<Integer, Pair<ExecutionReference,ExecutableFlow>> handle(ResultSet rs) throws SQLException {
			if (!rs.next()) {
				return Collections.<Integer, Pair<ExecutionReference,ExecutableFlow>>emptyMap();
			}

			Map<Integer, Pair<ExecutionReference,ExecutableFlow>> execFlows = new HashMap<Integer, Pair<ExecutionReference,ExecutableFlow>>();
			do {
				int id = rs.getInt(1);
				int encodingType = rs.getInt(2);
				byte[] data = rs.getBytes(3);
				String host = rs.getString(4);
				int port = rs.getInt(5);
				long updateTime = rs.getLong(6);
				
				if (data == null) {
					execFlows.put(id, null);
				}
				else {
					EncodingType encType = EncodingType.fromInteger(encodingType);
					Object flowObj;
					try {
						// Convoluted way to inflate strings. Should find common package or helper function.
						if (encType == EncodingType.GZIP) {
							// Decompress the sucker.
							String jsonString = GZIPUtils.unGzipString(data, "UTF-8");
							flowObj = JSONUtils.parseJSONFromString(jsonString);
						}
						else {
							String jsonString = new String(data, "UTF-8");
							flowObj = JSONUtils.parseJSONFromString(jsonString);
						}
						
						ExecutableFlow exFlow = ExecutableFlow.createExecutableFlowFromObject(flowObj);
						ExecutionReference ref = new ExecutionReference(id, host, port);
						ref.setUpdateTime(updateTime);
						
						execFlows.put(id, new Pair<ExecutionReference, ExecutableFlow>(ref, exFlow));
					} catch (IOException e) {
						throw new SQLException("Error retrieving flow data " + id, e);
					}
				}
			} while (rs.next());

			return execFlows;
		}
		
	}
	
	private static class FetchExecutableFlows implements ResultSetHandler<List<ExecutableFlow>> {
		private static String FETCH_BASE_EXECUTABLE_FLOW_QUERY = "SELECT exec_id, enc_type, flow_data FROM execution_flows ";
		private static String FETCH_EXECUTABLE_FLOW = "SELECT exec_id, enc_type, flow_data FROM execution_flows WHERE exec_id=?";
		private static String FETCH_ACTIVE_EXECUTABLE_FLOW = "SELECT ex.exec_id exec_id, ex.enc_type enc_type, ex.flow_data flow_data FROM execution_flows ex INNER JOIN active_executing_flows ax ON ex.exec_id = ax.exec_id";
		private static String FETCH_ALL_EXECUTABLE_FLOW_HISTORY = "SELECT exec_id, enc_type, flow_data FROM execution_flows ORDER BY exec_id DESC LIMIT ?, ?";
		private static String FETCH_EXECUTABLE_FLOW_HISTORY = "SELECT exec_id, enc_type, flow_data FROM execution_flows WHERE project_id=? AND flow_id=? ORDER BY exec_id DESC LIMIT ?, ?";
		
		@Override
		public List<ExecutableFlow> handle(ResultSet rs) throws SQLException {
			if (!rs.next()) {
				return Collections.<ExecutableFlow>emptyList();
			}

			List<ExecutableFlow> execFlows = new ArrayList<ExecutableFlow>();
			do {
				int id = rs.getInt(1);
				int encodingType = rs.getInt(2);
				byte[] data = rs.getBytes(3);
				
				if (data != null) {
					EncodingType encType = EncodingType.fromInteger(encodingType);
					Object flowObj;
					try {
						// Convoluted way to inflate strings. Should find common package or helper function.
						if (encType == EncodingType.GZIP) {
							// Decompress the sucker.
							String jsonString = GZIPUtils.unGzipString(data, "UTF-8");
							flowObj = JSONUtils.parseJSONFromString(jsonString);
						}
						else {
							String jsonString = new String(data, "UTF-8");
							flowObj = JSONUtils.parseJSONFromString(jsonString);
						}
						
						ExecutableFlow exFlow = ExecutableFlow.createExecutableFlowFromObject(flowObj);
						execFlows.add(exFlow);
					} catch (IOException e) {
						throw new SQLException("Error retrieving flow data " + id, e);
					}
				}
			} while (rs.next());

			return execFlows;
		}
		
	}
	
	private static class IntHandler implements ResultSetHandler<Integer> {
		private static String NUM_EXECUTIONS = "SELECT COUNT(1) FROM execution_flows";
		private static String NUM_FLOW_EXECUTIONS = "SELECT COUNT(1) FROM execution_flows WHERE project_id=? AND flow_id=?";
		private static String NUM_JOB_EXECUTIONS = "SELECT COUNT(1) FROM execution_jobs WHERE project_id=? AND job_id=?";
		
		@Override
		public Integer handle(ResultSet rs) throws SQLException {
			if (!rs.next()) {
				return 0;
			}
			
			return rs.getInt(1);
		}
	}
}