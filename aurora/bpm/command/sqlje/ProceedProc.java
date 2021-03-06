package aurora.bpm.command.sqlje;

import uncertain.composite.*;
import aurora.bpm.command.beans.*;
import java.sql.*;
import java.util.List;
import aurora.sqlje.exception.*;
import java.util.Map;
import aurora.sqlje.core.*;

public class ProceedProc implements ISqlCallEnabled {
	public void proceed(Long instance_id, Long path_id) throws Exception {
		String _$sqlje_sql_gen3 = "select * from bpm_path_instance where instance_id=? and path_id=?";
		PreparedStatement _$sqlje_ps_gen2 = getSqlCallStack()
				.getCurrentConnection().prepareStatement(_$sqlje_sql_gen3);
		_$sqlje_ps_gen2.setLong(1, instance_id);
		_$sqlje_ps_gen2.setLong(2, path_id);
		$sql.clear();
		_$sqlje_ps_gen2.execute();
		$sql.UPDATECOUNT = _$sqlje_ps_gen2.getUpdateCount();
		ResultSet _$sqlje_rs_gen0 = _$sqlje_ps_gen2.getResultSet();
		getSqlCallStack().push(_$sqlje_rs_gen0);
		getSqlCallStack().push(_$sqlje_ps_gen2);
		BpmnPathInstance path = DataTransfer.transfer1(BpmnPathInstance.class,
				_$sqlje_rs_gen0);
		if ("ACTIVE".equals(path.status)) {
			String _$sqlje_sql_gen5 = "select * from bpm_process_instance where instance_id=?";
			PreparedStatement _$sqlje_ps_gen4 = getSqlCallStack()
					.getCurrentConnection().prepareStatement(_$sqlje_sql_gen5);
			_$sqlje_ps_gen4.setLong(1, instance_id);
			$sql.clear();
			_$sqlje_ps_gen4.execute();
			$sql.UPDATECOUNT = _$sqlje_ps_gen4.getUpdateCount();
			ResultSet _$sqlje_rs_gen1 = _$sqlje_ps_gen4.getResultSet();
			getSqlCallStack().push(_$sqlje_rs_gen1);
			getSqlCallStack().push(_$sqlje_ps_gen4);
			BpmnProcessInstance instance = DataTransfer
					.transfer1(BpmnProcessInstance.class, _$sqlje_rs_gen1);
		}
	}

	protected ISqlCallStack _$sqlje_sqlCallStack = null;
	protected IInstanceManager _$sqlje_instanceManager = null;
	protected SqlFlag $sql = new SqlFlag(this);

	public ISqlCallStack getSqlCallStack() {
		return _$sqlje_sqlCallStack;
	}

	public void _$setSqlCallStack(ISqlCallStack args0) {
		_$sqlje_sqlCallStack = args0;
	}

	public IInstanceManager getInstanceManager() {
		return _$sqlje_instanceManager;
	}

	public void _$setInstanceManager(IInstanceManager args0) {
		_$sqlje_instanceManager = args0;
	}
}
