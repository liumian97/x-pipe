package com.ctrip.xpipe.redis.console.migration.model.impl;

import com.ctrip.xpipe.api.codec.Codec;
import com.ctrip.xpipe.api.command.Command;
import com.ctrip.xpipe.api.command.CommandFuture;
import com.ctrip.xpipe.api.command.CommandFutureListener;
import com.ctrip.xpipe.api.observer.Observable;
import com.ctrip.xpipe.observer.AbstractObservable;
import com.ctrip.xpipe.redis.console.migration.command.MigrationCommandBuilder;
import com.ctrip.xpipe.redis.console.migration.command.MigrationCommandBuilderImpl;
import com.ctrip.xpipe.redis.console.migration.command.result.ShardMigrationResult;
import com.ctrip.xpipe.redis.console.migration.command.result.ShardMigrationResult.ShardMigrationResultStatus;
import com.ctrip.xpipe.redis.console.migration.command.result.ShardMigrationResult.ShardMigrationStep;
import com.ctrip.xpipe.redis.console.migration.model.MigrationCluster;
import com.ctrip.xpipe.redis.console.migration.model.MigrationShard;
import com.ctrip.xpipe.redis.console.model.DcTbl;
import com.ctrip.xpipe.redis.console.model.MigrationShardTbl;
import com.ctrip.xpipe.redis.console.model.ShardTbl;
import com.ctrip.xpipe.redis.console.service.migration.MigrationService;
import com.ctrip.xpipe.redis.core.metaserver.MetaServerConsoleService.PRIMARY_DC_CHANGE_RESULT;
import com.ctrip.xpipe.redis.core.metaserver.MetaServerConsoleService.PRIMARY_DC_CHECK_RESULT;
import com.ctrip.xpipe.redis.core.metaserver.MetaServerConsoleService.PrimaryDcChangeMessage;
import com.ctrip.xpipe.redis.core.metaserver.MetaServerConsoleService.PrimaryDcCheckMessage;
import com.ctrip.xpipe.utils.LogUtils;
import com.ctrip.xpipe.utils.XpipeThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author shyin
 *
 * Dec 8, 2016
 */
public class DefaultMigrationShard extends AbstractObservable implements MigrationShard {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	private static Codec coder = Codec.DEFAULT;
	
	private ExecutorService executors;
	
	private MigrationCluster parent;
	private MigrationShardTbl migrationShard;
	private ShardMigrationResult shardMigrationResult;
	
	private MigrationService migrationService;
	
	private ShardTbl currentShard;
	private Map<Long, DcTbl> dcs;

	private MigrationCommandBuilder commandBuilder;
	
	private InetSocketAddress newMasterAddr;
	
	private String cluster;
	private String shard;
	private String newPrimaryDc;
	private String prevPrimaryDc;

	public DefaultMigrationShard(MigrationCluster parent, MigrationShardTbl migrationShard, ShardTbl currentShard,Map<Long, DcTbl> dcs,
			MigrationService migrationService) {
		this(parent, migrationShard, currentShard, dcs, migrationService, MigrationCommandBuilderImpl.INSTANCE);
	}

	public DefaultMigrationShard(MigrationCluster parent, MigrationShardTbl migrationShard, ShardTbl currentShard,Map<Long, DcTbl> dcs,
								 MigrationService migrationService, MigrationCommandBuilder commandBuilder) {
		this.parent = parent;
		this.migrationShard = migrationShard;
		this.currentShard = currentShard;
		this.dcs = dcs;
		this.migrationService = migrationService;
		shardMigrationResult = new ShardMigrationResult();
		this.commandBuilder = commandBuilder;
		this.newMasterAddr = null;
		

		cluster = parent.getCurrentCluster().getClusterName();
		shard = currentShard.getShardName();
		newPrimaryDc = dcs.get(parent.getMigrationCluster().getDestinationDcId()).getDcName();
		prevPrimaryDc = dcs.get(parent.getCurrentCluster().getActivedcId()).getDcName();

		executors = Executors.newCachedThreadPool(XpipeThreadFactory.create("[migrate]" + cluster+ "," + shard));
		addObserver(parent);
		addObserver(this);
	}

	@Override
	public MigrationShardTbl getMigrationShard() {
		return migrationShard;
	}
	
	@Override
	public ShardMigrationResult getShardMigrationResult() {
		return shardMigrationResult;
	}
	
	@Override
	public ShardTbl getCurrentShard() {
		return currentShard;
	}
	
	@Override
	public InetSocketAddress getNewMasterAddress() {
		return newMasterAddr;
	}

	@Override
	public void update(Object args, Observable observable) {
		MigrationShardTbl toUpdate = getMigrationShard();
		toUpdate.setLog(coder.encode(getShardMigrationResult()));
		migrationService.updateMigrationShard(toUpdate);
		
	}
	
	@Override
	public void doCheck() {
		
		logger.info("[doCheck]{}-{}-{}", cluster, shard, newPrimaryDc);
		CommandFuture<PrimaryDcCheckMessage> checkResult = commandBuilder.buildDcCheckCommand(cluster, shard, newPrimaryDc, newPrimaryDc).execute();
		checkResult.addListener(new CommandFutureListener<PrimaryDcCheckMessage>() {
			@Override
			public void operationComplete(CommandFuture<PrimaryDcCheckMessage> commandFuture)
					throws Exception {
				try {
					PrimaryDcCheckMessage res = commandFuture.get();
					if(PRIMARY_DC_CHECK_RESULT.SUCCESS.equals(res.getErrorType())){
						shardMigrationResult.updateStepResult(ShardMigrationStep.CHECK, true, LogUtils.info("Check success"));
					} else {
						shardMigrationResult.updateStepResult(ShardMigrationStep.CHECK, false, LogUtils.error(res.getErrorMessage()));
					}
				} catch (ExecutionException e) {
					logger.error("[doCheck][fail]",e);
					shardMigrationResult.updateStepResult(ShardMigrationStep.CHECK, false, LogUtils.error(e.getMessage()));
				}
				
				notifyObservers(this);
			}
		});
	}
	
	@Override
	public void doMigrate() {
		
		logger.info("[doMigrate]{}-{}, {}->{}", cluster, shard, prevPrimaryDc, newPrimaryDc);
		try {
			doPrevPrimaryDcMigrate(cluster, shard, prevPrimaryDc).get();
		} catch (InterruptedException | ExecutionException e) {
			shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_PREVIOUS_PRIMARY_DC, true, LogUtils.error("Ignore:" + e.getMessage()));
		}
		
		try {
			doNewPrimaryDcMigrate(cluster, shard, newPrimaryDc).get();
		} catch (InterruptedException | ExecutionException e) {
			shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_NEW_PRIMARY_DC, false, LogUtils.error(e.getMessage()));
		}
		
		notifyObservers(this);
	}
	
	@Override
	public void doMigrateOtherDc() {
		
		logger.info("[doMigrateOtherDc]{}-{}, {}->{}", cluster, shard, prevPrimaryDc, newPrimaryDc);
		if(shardMigrationResult.stepSuccess(ShardMigrationStep.MIGRATE_NEW_PRIMARY_DC)) {
			for(DcTbl dc : dcs.values()) {
				if(!(dc.getDcName().equals(newPrimaryDc))) {
					doOtherDcMigrate(cluster, shard, dc.getDcName(), newPrimaryDc);
				}
			}
		}
		
		if(shardMigrationResult.stepSuccess(ShardMigrationStep.MIGRATE_NEW_PRIMARY_DC)) {
			shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE, true, LogUtils.info("Success"));
			shardMigrationResult.setStatus(ShardMigrationResultStatus.SUCCESS);
		} else {
			shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE, false, LogUtils.error("Failed"));
		}

		notifyObservers(this);
	}

	@Override
	public void doRollBack() throws Exception{
		
		logger.info("[rollback]{}-{}, {}<-{}", cluster, shard, prevPrimaryDc, newPrimaryDc);
		for(DcTbl dc : dcs.values()) {
			if(!(dc.getDcName().equals(prevPrimaryDc))) {
				doOtherDcRollback(dc.getDcName(), prevPrimaryDc);
			}
		}
		
		doRollBackPrevPrimaryDc(cluster, shard, prevPrimaryDc).get();
	}
	
	private CommandFuture<PrimaryDcChangeMessage> doPrevPrimaryDcMigrate(String cluster, String shard, String dc) {
		CommandFuture<PrimaryDcChangeMessage> migrateResult = commandBuilder.buildPrevPrimaryDcCommand(cluster, shard, dc).execute();
		migrateResult.addListener(new CommandFutureListener<PrimaryDcChangeMessage>() {
			@Override
			public void operationComplete(CommandFuture<PrimaryDcChangeMessage> commandFuture) throws Exception {
				try {
					commandFuture.get();
					
					shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_PREVIOUS_PRIMARY_DC, true, LogUtils.info("Ignored : make previous primary dc read only"));
				} catch (Exception e) {
					logger.error("[doPrevPrimaryDcMigrate][fail]",e);
					shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_PREVIOUS_PRIMARY_DC, true, LogUtils.error("Ignored:" + e.getMessage()));
				}
				
				notifyObservers(this);
			}
		});
		return migrateResult;
	}

	private CommandFuture<PrimaryDcChangeMessage> doNewPrimaryDcMigrate(String cluster, String shard, String newPrimaryDc) {
		CommandFuture<PrimaryDcChangeMessage> migrateResult = commandBuilder.buildNewPrimaryDcCommand(cluster, shard, newPrimaryDc).execute();
		migrateResult.addListener(new CommandFutureListener<PrimaryDcChangeMessage>() {
			@Override
			public void operationComplete(CommandFuture<PrimaryDcChangeMessage> commandFuture) throws Exception {
				try {
					PrimaryDcChangeMessage res = commandFuture.get();
					
					if(PRIMARY_DC_CHANGE_RESULT.SUCCESS.equals(res.getErrorType())) {
						shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_NEW_PRIMARY_DC, true, res.getErrorMessage());
						
						if(null != res.getNewMasterIp()) {
							newMasterAddr = InetSocketAddress.createUnresolved(res.getNewMasterIp(), res.getNewMasterPort());
						}
					} else {
						shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_NEW_PRIMARY_DC, false, res.getErrorMessage());
					}
				} catch (Exception e) {
					logger.error("[doNewPrimaryDcMigrate][fail]",e);
					shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_NEW_PRIMARY_DC, false, LogUtils.error(e.getMessage()));
				}
				
				notifyObservers(this);
			}
		});
		return migrateResult;
	}
	
	private void doOtherDcRollback(String dc, String prevPrimaryDc) {

		 Command<PrimaryDcChangeMessage>  command = commandBuilder.buildOtherDcCommand(cluster, shard, prevPrimaryDc, dc);
		 if(command == null){
			 logger.warn("[doOtherDcRollback][fail, command null]{}", this);
			 return;
		 }
		 CommandFuture<PrimaryDcChangeMessage> migrateResult = command.execute(executors);
		migrateResult.addListener(new CommandFutureListener<PrimaryDcChangeMessage>() {

			@Override
			public void operationComplete(CommandFuture<PrimaryDcChangeMessage> commandFuture) throws Exception {
				if(!commandFuture.isSuccess()){
					logger.error("[doOtherDcRollback]" + cluster + "," + shard, commandFuture.cause());
				}else{
					PrimaryDcChangeMessage primaryDcChangeMessage = commandFuture.get();
					logger.info("[doOtherDcRollback]{}, {}, {}", cluster, shard, primaryDcChangeMessage);
				}
			}
		});

	}


	
	private CommandFuture<PrimaryDcChangeMessage> doOtherDcMigrate(String cluster, String shard, String dc, String newPrimaryDc) {
		CommandFuture<PrimaryDcChangeMessage> migrateResult = commandBuilder.buildOtherDcCommand(cluster, shard, newPrimaryDc, dc).execute();
		migrateResult.addListener(new CommandFutureListener<PrimaryDcChangeMessage>() {
			@Override
			public void operationComplete(CommandFuture<PrimaryDcChangeMessage> commandFuture) throws Exception {
				try {
					PrimaryDcChangeMessage res = commandFuture.get();
					
					if(PRIMARY_DC_CHANGE_RESULT.SUCCESS.equals(res.getErrorType())) {
						shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_OTHER_DC, true, res.getErrorMessage());
					} else {
						shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_OTHER_DC, false, res.getErrorMessage());
					}
				} catch (Exception e) {
					logger.error("[doOtherDcMigrate][fail]",e);
					shardMigrationResult.updateStepResult(ShardMigrationStep.MIGRATE_OTHER_DC, false, e.getMessage());
				}
				
				notifyObservers(this);
			}
		});
		return migrateResult;
	}

	private CommandFuture<PrimaryDcChangeMessage> doRollBackPrevPrimaryDc(String cluster, String shard, String dc) {
		CommandFuture<PrimaryDcChangeMessage> migrateResult = commandBuilder.buildRollBackCommand(cluster, shard, dc).execute();
		migrateResult.addListener(new CommandFutureListener<PrimaryDcChangeMessage>() {
			@Override
			public void operationComplete(CommandFuture<PrimaryDcChangeMessage> commandFuture) throws Exception {
				try {
					commandFuture.get();
					logger.info("[doPrevPrimaryDcMigrate][success]");
				} catch (Exception e) {
					logger.error("[doPrevPrimaryDcMigrate][fail]",e);
				}

				notifyObservers(this);
			}
		});
		return migrateResult;
	}

	@Override
	public String toString() {
		return String.format("[DefaultMigrationShard]%s:%s,%s->%s", cluster, shard, prevPrimaryDc, newPrimaryDc);
	}
}
