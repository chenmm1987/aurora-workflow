package aurora.bpm.command;

import java.sql.Connection;

import javax.sql.DataSource;

import org.eclipse.bpmn2.Definitions;
import org.eclipse.bpmn2.FlowNode;
import org.eclipse.bpmn2.SequenceFlow;

import uncertain.composite.CompositeMap;
import aurora.bpm.command.sqlje.BpmnProcessInstance;
import aurora.bpm.command.sqlje.instance;
import aurora.bpm.command.sqlje.path;
import aurora.bpm.engine.ExecutorContext;
import aurora.bpm.script.BPMScriptEngine;
import aurora.database.service.IDatabaseServiceFactory;
import aurora.sqlje.core.ISqlCallEnabled;
import aurora.sqlje.core.ISqlCallStack;
import aurora.sqlje.core.SqlCallStack;

public abstract class AbstractCommandExecutor implements ICommandExecutor {

	public static final String INSTANCE_ID = "instance_id";
	public static final String PROCESS_CODE = "process_code";
	public static final String PROCESS_VERSION = "process_version";
	public static final String NODE_ID = "node_id";
	public static final String RECORD_ID = "record_id";
	public static final String USER_ID = "user_id";
	public static final String[] STANDARD_PROPERTIES = { INSTANCE_ID,
			PROCESS_CODE, PROCESS_VERSION, RECORD_ID, USER_ID };

	private ExecutorContext context;
	protected IDatabaseServiceFactory dsf;

	public AbstractCommandExecutor(IDatabaseServiceFactory dsf) {
		this.dsf = dsf;
	}

	public void init(ExecutorContext context) {
		this.context = context;
	}

	public ExecutorContext getExecutorContext() {
		return context;
	}

	protected IDatabaseServiceFactory getDatabaseServiceFactory() {
		return dsf;
	}

	/**
	 * create a new SqlCallStack
	 * 
	 * @return
	 * @throws Exception
	 */
	protected ISqlCallStack createSqlCallStack() throws Exception {
		DataSource ds = getDatabaseServiceFactory().getDataSource();
		Connection conn = ds.getConnection();
		conn.setAutoCommit(false);
		ISqlCallStack callStack = new SqlCallStack(ds, conn);
		CompositeMap contextData = new CompositeMap("context");
		callStack.setContextData(contextData);
		return callStack;
	}

	/**
	 * release a SqlCallStack
	 * 
	 * @param callStack
	 * @throws Exception
	 */
	protected void releaseSqlCallStack(ISqlCallStack callStack)
			throws Exception {
		if (callStack != null)
			callStack.cleanUp();
	}

	@Override
	public void execute(Command cmd) throws Exception {
		ISqlCallStack callStack = createSqlCallStack();
		try {
			Long instance_id = cmd.getOptions().getLong(INSTANCE_ID);
			boolean running = true;
			if (instance_id != null) {
				instance inst = createProc(instance.class, callStack);
				BpmnProcessInstance bpi = inst.query(instance_id);
				cmd.getOptions().put(PROCESS_CODE, bpi.process_code);
				cmd.getOptions().put(PROCESS_VERSION, bpi.process_version);
				running = eq(bpi.status, "RUNNING");
			}
			if (running)
				executeWithSqlCallStack(callStack, cmd);
			callStack.commit();
		} catch (Exception e) {
			callStack.rollback();
			throw e;
		} finally {
			releaseSqlCallStack(callStack);
		}
	}

	@Override
	public void executeWithSqlCallStack(ISqlCallStack callStack, Command cmd)
			throws Exception {

	}

	protected BPMScriptEngine prepareScriptEngine(ISqlCallStack callStack,
			Command cmd) {
		BPMScriptEngine engine = getExecutorContext().createScriptEngine(
				callStack.getContextData());
		engine.registry("callStack", callStack);
		engine.registry("command", cmd);
		return engine;
	}

	/**
	 * find all outgoing(s) ,and
	 * {@link #createPath(ISqlCallStack,SequenceFlow,Command)} for each outgoing
	 * 
	 * @param callStack
	 * @param node
	 * @param cmd
	 * @throws Exception
	 */
	protected void createOutgoingPath(ISqlCallStack callStack, FlowNode node,
			Command cmd) throws Exception {
		for (SequenceFlow sf : node.getOutgoing()) {
			createPath(callStack, sf, cmd);
		}
	}

	protected org.eclipse.bpmn2.Definitions loadDefinitions(String code,
			String version, ISqlCallStack callStack) throws Exception {
		return getExecutorContext().getDefinitionFactory().loadDefinition(code,
				version, callStack);
	}

	protected Definitions loadDefinitions(Command cmd, ISqlCallStack callStack)
			throws Exception {
		return loadDefinitions(cmd.getOptions().getString(PROCESS_CODE), cmd
				.getOptions().getString(PROCESS_VERSION), callStack);
	}

	protected <T extends ISqlCallEnabled> T createProc(Class<T> clazz,
			ISqlCallStack callStack) {
		T t = getExecutorContext().getInstanceManager().createInstance(clazz);
		t._$setSqlCallStack(callStack);
		return t;
	}

	public static boolean eq(Object o1, Object o2) {
		if (o1 == null)
			return o2 == null;
		return o1.equals(o2);
	}

	/**
	 * copy <code>STANDARD_PROPERTIES</code> from <code>cmd0</code>
	 * 
	 * @param cmd0
	 * @return {@link STANDARD_PROPERTIES}
	 */
	protected CompositeMap createOptionsWithStandardInfo(Command cmd0) {
		CompositeMap map = new CompositeMap();
		for (String p : STANDARD_PROPERTIES) {
			map.put(p, cmd0.getOptions().getString(p));
		}
		return map;
	}

	protected CompositeMap cloneOptions(Command cmd) {
		return (CompositeMap) cmd.getOptions().clone();
	}

	/**
	 * find CommandExecutor for <code>cmd2</code> ,and execute it with
	 * <code>callStack</code>
	 * 
	 * @param callStack
	 * @param cmd2
	 * @throws Exception
	 */
	protected void dispatchCommand(ISqlCallStack callStack, Command cmd2)
			throws Exception {
		ICommandExecutor executor = getExecutorContext().getCommandRegistry()
				.findExecutor(cmd2);
		executor.executeWithSqlCallStack(callStack, cmd2);
	}

	/**
	 * create a path ,and call PROCEED
	 * 
	 * @throws Exception
	 */
	protected void createPath(ISqlCallStack callStack, SequenceFlow sf,
			Command cmd) throws Exception {
		path cp = createProc(path.class, callStack);
		Long instance_id = cmd.getOptions().getLong(INSTANCE_ID);
		Long path_id = cp.create(instance_id, sf.getSourceRef().getId(), sf
				.getTargetRef().getId());
		System.out.println("path created ,id:" + path_id);

		CompositeMap opts = createOptionsWithStandardInfo(cmd);
		opts.put("path_id", path_id);
		// create a PROCEED command
		Command cmd2 = new Command(ProceedCmdExecutor.TYPE, opts);
		dispatchCommand(callStack, cmd2);
	}

	/**
	 * get process from definitions
	 * 
	 * @param def
	 * @return
	 */
	protected org.eclipse.bpmn2.Process getProcess(Definitions def) {

		return (org.eclipse.bpmn2.Process) def.eContents().get(0);

	}

	protected org.eclipse.bpmn2.FlowElement findFlowElementById(
			org.eclipse.bpmn2.Process process, String id) {
		for (org.eclipse.bpmn2.FlowElement fe : process.getFlowElements())
			if (eq(fe.getId(), id))
				return fe;
		return null;
	}

	protected <T extends org.eclipse.bpmn2.FlowElement> T findFlowElementById(
			org.eclipse.bpmn2.Process process, String id, Class<T> type) {
		for (org.eclipse.bpmn2.FlowElement fe : process.getFlowElements())
			if (fe != null && type.isAssignableFrom(fe.getClass())
					& eq(fe.getId(), id))
				return (T) fe;
		return null;
	}

}
