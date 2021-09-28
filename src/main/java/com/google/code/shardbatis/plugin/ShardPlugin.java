/**
 * 
 */
package com.google.code.shardbatis.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.google.code.shardbatis.builder.ShardConfigHolder;
import com.google.code.shardbatis.builder.ShardConfigParser;
import com.google.code.shardbatis.converter.SqlConverterFactory;
import com.google.code.shardbatis.util.ReflectionUtils;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * @author sean.he
 * 
 */
@Intercepts({ @Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }),
		@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
				RowBounds.class, ResultHandler.class }),
		@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
				RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class }),
		@Signature(type = Executor.class, method = "queryCursor", args = { MappedStatement.class, Object.class,
				RowBounds.class }),

		@Signature(type = StatementHandler.class, method = "prepare", args = { Connection.class, Integer.class }), })
public class ShardPlugin implements Interceptor {
	private static final Log log = LogFactory.getLog(ShardPlugin.class);

	public static final String SHARDING_CONFIG = "shardingConfig";
	private static final ThreadLocal<String> THREADLOCAL_MAPPERID = new ThreadLocal<String>();

	// ConcurrentHashMap<mapperId,needParse>
	private static final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<String, Boolean>();

	public Object intercept(Invocation invocation) throws Throwable {
		if (invocation.getTarget() instanceof Executor) {
			try {
				String mapperId = ((MappedStatement) invocation.getArgs()[0]).getId();
				THREADLOCAL_MAPPERID.set(mapperId);
				return invocation.proceed();
			} finally {
				THREADLOCAL_MAPPERID.remove();
			}
		} else {
			StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
			String mapperId = THREADLOCAL_MAPPERID.get();
			if (isShouldParse(mapperId)) {
				String sql = statementHandler.getBoundSql().getSql();
				if (log.isDebugEnabled()) {
					log.debug("Original Sql [" + mapperId + "]:" + sql);
				}
				Object params = statementHandler.getBoundSql().getParameterObject();

				SqlConverterFactory cf = SqlConverterFactory.getInstance();
				sql = cf.convert(sql, params, mapperId);
				if (log.isDebugEnabled()) {
					log.debug("Converted Sql [" + mapperId + "]:" + sql);
				}
				ReflectionUtils.setFieldValue(statementHandler.getBoundSql(), "sql", sql);
			}
			return invocation.proceed();
		}
	}

	public Object plugin(Object target) {
		return Plugin.wrap(target, this);
	}

	public void setProperties(Properties properties) {
		// 解析配置文件
		String config = properties.getProperty(SHARDING_CONFIG, null);
		if (config == null || config.trim().length() == 0) {
			throw new IllegalArgumentException("property 'shardingConfig' is requested.");
		}

		ShardConfigParser parser = new ShardConfigParser();
		InputStream input = null;

		try {
			input = Resources.getResourceAsStream(config);
			parser.parse(input);
		} catch (IOException e) {
			log.error("Get sharding config file failed.", e);
			throw new IllegalArgumentException(e);
		} catch (Exception e) {
			log.error("Parse sharding config file failed.", e);
			throw new IllegalArgumentException(e);
		} finally {
			if (input != null) {
				try {
					input.close();
				} catch (IOException e) {
					log.error(e.getMessage(), e);
				}
			}
		}

	}

	private boolean isShouldParse(String mapperId) {
		Boolean parse = cache.get(mapperId);

		if (parse != null) {//已被缓存
			return parse;
		}
		/*
		 * 1.<selectKey>不做解析
		 * 2.在ignoreList里的sql不用处理
		 * 3.如果不在ignoreList里并且没有配置parseList则进行处理
		 * 4.如果不在ignoreList里并且也在parseList里则进行处理
		 * 5.如果不在ignoreList里并且也不在parseList里则不进行处理
		 */
		if (!mapperId.endsWith("!selectKey")) {

			ShardConfigHolder configHolder = ShardConfigHolder.getInstance();

			if (!configHolder.isIgnoreId(mapperId)) {
				if (!configHolder.isConfigParseId() || configHolder.isParseId(mapperId)) {

					parse = true;
				}
			}
		}
		if (parse == null) {
			parse = false;
		}
		cache.put(mapperId, parse);
		return parse;
	}
}
