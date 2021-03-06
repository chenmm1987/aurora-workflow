package aurora.bpm.command;

import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.eclipse.bpmn2.Definitions;
import org.eclipse.bpmn2.FlowElementsContainer;
import org.eclipse.bpmn2.FlowNode;
import org.eclipse.bpmn2.Process;
import org.eclipse.bpmn2.SequenceFlow;
import org.eclipse.emf.ecore.EObject;

import aurora.bpm.command.beans.BpmnProcessData;
import aurora.bpm.command.beans.BpmnProcessInstance;
import aurora.bpm.command.sqlje.InstanceProc;
import aurora.bpm.command.sqlje.PathProc;
import aurora.bpm.define.FlowElement;
import aurora.bpm.engine.ExecutorContext;
import aurora.bpm.queue.ICommandQueue;
import aurora.bpm.script.BPMScriptEngine;
import aurora.database.service.IDatabaseServiceFactory;
import aurora.plugin.script.scriptobject.ScriptShareObject;
import aurora.sqlje.core.ISqlCallEnabled;
import aurora.sqlje.core.ISqlCallStack;
import aurora.sqlje.core.SqlCallStack;
import uncertain.composite.CompositeMap;

public abstract class AbstractCommandExecutor implements ICommandExecutor {

	public static final String INSTANCE_ID = "instance_id";
	public static final String PROCESS_CODE = "process_code";
	public static final String PROCESS_VERSION = "process_version";
	public static final String SCOPE_ID = "scope_id";
	public static final String RECORD_ID = "record_id";
	public static final String USER_ID = "user_id";
	public static final String[] STANDARD_PROPERTIES = { ICommandQueue.QUEUE_ID,
			INSTANCE_ID, PROCESS_CODE, SCOPE_ID, PROCESS_VERSION, RECORD_ID,
			USER_ID };
	public static final String NODE_ID = "node_id";
	public static final String PATH_ID = "path_id";
	public static final String SEQUENCE_FLOW_ID = "sequence_flow_id";

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
		contextData.createChild("session");
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
		CompositeMap session = callStack.getContextData().getChild("session");
		session.put("user_id", cmd.getOptions().getString("user_id"));
		session.put("lang", cmd.getOptions().getString("lang"));
		try {
			Long instance_id = cmd.getOptions().getLong(INSTANCE_ID);
			boolean running = true;
			if (instance_id != null) {
				InstanceProc inst = createProc(InstanceProc.class, callStack);
				BpmnProcessInstance bpi = inst.query(instance_id);
				cmd.getOptions().put(PROCESS_CODE, bpi.process_code);
				cmd.getOptions().put(PROCESS_VERSION, bpi.process_version);
				cmd.getOptions().put(SCOPE_ID, bpi.scope_id);
				running = eq(bpi.status, "RUNNING");
				if (running) {
					loadDataObject(inst, instance_id, callStack);
				} else {
					System.err.println("process status:" + bpi.status
							+ "(instance_id:" + instance_id + ")");
				}
			}
			if (running && canExecute(callStack, cmd)) {
				executeWithSqlCallStack(callStack, cmd);
				saveDataObject(callStack,
						cmd.getOptions().getLong(INSTANCE_ID));
			}
			callStack.commit();
		} catch (Exception e) {
			callStack.rollback();
			throw e;
		} finally {
			releaseSqlCallStack(callStack);
		}
	}

	protected void loadDataObject(InstanceProc ci, Long instance_id,
			ISqlCallStack callStack) throws Exception {
		// prepare data_object($data)
		BpmnProcessData data = ci.getProcessData(instance_id);
		if (data != null) {
			callStack.getContextData().put("$json", data.data_object);
		}
	}

	private void saveDataObject(ISqlCallStack callStack, Long instance_id)
			throws Exception {
		// save data_object
		ScriptShareObject sso = (ScriptShareObject) callStack.getContextData()
				.get(BPMScriptEngine.KEY_SSO);
		if (sso == null) {
			// engine not use
			return;
		}
		BPMScriptEngine engine = (BPMScriptEngine) sso.getEngine();
		if (engine == null) {
			// engine not initialize
			return;
		}
		Object obj = engine.eval("JSON.stringify($ctx.data||{})");
		BpmnProcessData data = new BpmnProcessData();
		data.instance_id = instance_id;
		data.data_object = obj.toString();
		InstanceProc inst = createProc(InstanceProc.class, callStack);
		inst.saveDataObject(data);
	}

	protected boolean canExecute(ISqlCallStack callStack, Command cmd) {
		return true;
	}

	@Override
	public void executeWithSqlCallStack(ISqlCallStack callStack, Command cmd)
			throws Exception {

	}

	protected BPMScriptEngine prepareScriptEngine(ISqlCallStack callStack,
			Command cmd) {
		BPMScriptEngine engine = getExecutorContext()
				.createScriptEngine(callStack.getContextData());
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

	protected Definitions loadDefinitions(String code,
			String version, ISqlCallStack callStack) throws Exception {
		return getExecutorContext().getDefinitionFactory().loadDefinition(code,
				version, callStack);
	}

	protected Definitions loadDefinitions(Command cmd, ISqlCallStack callStack)
			throws Exception {
		return loadDefinitions(cmd.getOptions().getString(PROCESS_CODE),
				cmd.getOptions().getString(PROCESS_VERSION), callStack);
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
	 * @return {@code STANDARD_PROPERTIES}
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
		// MultiCommandQueue mcq = (MultiCommandQueue) getExecutorContext()
		// .getObjectRegistry().getInstanceOfType(MultiCommandQueue.class);
		// if (mcq != null) {
		// int queueId = cmd2.getOptions().getInt(ICommandQueue.QUEUE_ID);
		// mcq.getCommandQueue(queueId).offer(cmd2);
		// System.out.println("put a command into queue:" + cmd2);
		// return;
		// }
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
		PathProc cp = createProc(PathProc.class, callStack);
		Long instance_id = cmd.getOptions().getLong(INSTANCE_ID);
		Long path_id = cp.create(instance_id, sf.getSourceRef().getId(),
				sf.getTargetRef().getId(), sf.getId());
		System.out.printf("path <%s> created ,id:%d\n", sf.getId(), path_id);

		cp.createPathLog(instance_id, path_id, 1L, sf.getSourceRef().getId(),
				sf.getTargetRef().getId(), "");

		CompositeMap opts = createOptionsWithStandardInfo(cmd);
		opts.put(PATH_ID, path_id);
		opts.put(SEQUENCE_FLOW_ID, sf.getId());
		// create a PROCEED command
		Command cmd2 = new Command(ProceedCmdExecutor.TYPE, opts);
		dispatchCommand(callStack, cmd2);
	}

	/**
	 * get the root process from definitions
	 * 
	 * @param def
	 * @return
	 */
	protected Process getRootProcess(Definitions def) {
		List<EObject> contents = def.eContents();
		for (EObject eo : contents) {
			if (eo instanceof Process)
				return (Process) eo;
		}
		return null;

	}

	/**
	 * find a FlowElementContainer (usually a sub-process)
	 * 
	 * @param container
	 * @param id
	 * @return
	 */
	protected FlowElementsContainer findFlowElementContainerById(
			FlowElementsContainer container, String id) {
		if (eq(container.getId(), id)) {
			return container;
		}
		for (org.eclipse.bpmn2.FlowElement fe : container.getFlowElements()) {
			if ((fe instanceof FlowElementsContainer)) {
				if (eq(fe.getId(), id))
					return (FlowElementsContainer) fe;
				return findFlowElementContainerById((FlowElementsContainer) fe,
						id);
			}
		}
		return null;
	}

	protected org.eclipse.bpmn2.FlowElement findFlowElementById(
			FlowElementsContainer container, String id) {
		for (org.eclipse.bpmn2.FlowElement fe : container.getFlowElements())
			if (eq(fe.getId(), id))
				return fe;
		return null;
	}

	/**
	 * find a specified FlowElement with id and given type<br>
	 * search children only,no recursive
	 * 
	 * @param container
	 * @param id
	 * @param type
	 * @return FlowElement cast to given type
	 */
	protected <T extends org.eclipse.bpmn2.FlowElement> T findFlowElementById(
			FlowElementsContainer container, String id, Class<T> type) {
		for (org.eclipse.bpmn2.FlowElement fe : container.getFlowElements())
			if (fe != null && type.isAssignableFrom(fe.getClass())
					& eq(fe.getId(), id))
				return (T) fe;
		return null;
	}

}
