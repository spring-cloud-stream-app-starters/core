/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.tasklaunchrequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.app.tasklaunchrequest.support.CommandLineArgumentsMessageMapper;
import org.springframework.cloud.stream.app.tasklaunchrequest.support.TaskLaunchRequestSupplier;
import org.springframework.cloud.stream.app.tasklaunchrequest.support.TaskNameMessageMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.messaging.Message;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;


/**
 * @author David Turanski
 **/
@Configuration
@EnableConfigurationProperties(DataflowTaskLaunchRequestProperties.class)
public class DataFlowTaskLaunchRequestAutoConfiguration {

	public final static String TASK_LAUNCH_REQUEST_FUNCTION_NAME = "taskLaunchRequest";

	private static final Log log = LogFactory.getLog(DataFlowTaskLaunchRequestAutoConfiguration.class);

	private final DataflowTaskLaunchRequestProperties taskLaunchRequestProperties;

	private final Map<String,String> deploymentProperties;


	public DataFlowTaskLaunchRequestAutoConfiguration(DataflowTaskLaunchRequestProperties taskLaunchRequestProperties) {
		this.taskLaunchRequestProperties = taskLaunchRequestProperties;
		this.deploymentProperties = KeyValueListParser.parseCommaDelimitedKeyValuePairs(
			taskLaunchRequestProperties.getDeploymentProperties());

	}

	/**
	 * A {@link java.util.function.Function} to transform a {@link Message} payload to a {@link DataFlowTaskLaunchRequest}.
	 *
	 * @param taskLaunchRequestMessageProcessor a {@link TaskLaunchRequestMessageProcessor}.
	 *
	 * @return a {code DataFlowTaskLaunchRequest} Message.
	 */
	@Bean(name = TASK_LAUNCH_REQUEST_FUNCTION_NAME)
	@ConditionalOnMissingBean(TaskLaunchRequestFunction.class)
	public TaskLaunchRequestFunction taskLaunchRequest(TaskLaunchRequestMessageProcessor taskLaunchRequestMessageProcessor) {
		return message -> taskLaunchRequestMessageProcessor.postProcessMessage(message);
	}

	@Bean
	@ConditionalOnMissingBean(TaskNameMessageMapper.class)
	public TaskNameMessageMapper taskNameMessageMapper(DataflowTaskLaunchRequestProperties taskLaunchRequestProperties) {
		if (StringUtils.hasText(taskLaunchRequestProperties.getTaskNameExpression())) {
			SpelExpressionParser expressionParser = new SpelExpressionParser();
			Expression taskNameExpression = expressionParser.parseExpression(taskLaunchRequestProperties.getTaskNameExpression());
			return new ExpressionEvaluatingTaskNameMessageMapper(taskNameExpression);
		}

		return message -> taskLaunchRequestProperties.getTaskName();
	}

	@Bean
	@ConditionalOnMissingBean(CommandLineArgumentsMessageMapper.class)
	public CommandLineArgumentsMessageMapper commandLineArgumentsMessageMapper(
			DataflowTaskLaunchRequestProperties dataflowTaskLaunchRequestProperties){
		return new ExpressionEvaluatingCommandLineArgsMapper(dataflowTaskLaunchRequestProperties.getArgExpressions());
	}

	@Bean
	public TaskLaunchRequestSupplier taskLaunchRequestInitializer(
			DataflowTaskLaunchRequestProperties taskLaunchRequestProperties){
		return new DataflowTaskLaunchRequestPropertiesInitializer(taskLaunchRequestProperties);
	}

	@Bean
	public TaskLaunchRequestMessageProcessor taskLaunchRequestMessageProcessor(
			TaskLaunchRequestSupplier taskLaunchRequestInitializer,
			TaskNameMessageMapper taskNameMessageMapper,
			CommandLineArgumentsMessageMapper commandLineArgumentsMessageMapper) {

		return new TaskLaunchRequestMessageProcessor(taskLaunchRequestInitializer,
				taskNameMessageMapper,
				commandLineArgumentsMessageMapper);
	}

	static class DataflowTaskLaunchRequestPropertiesInitializer extends TaskLaunchRequestSupplier {
		DataflowTaskLaunchRequestPropertiesInitializer(
				DataflowTaskLaunchRequestProperties taskLaunchRequestProperties){

			this.commandLineArgumentSupplier(
					() -> new ArrayList<>(taskLaunchRequestProperties.getArgs())
			);

			this.deploymentPropertiesSupplier(
					() -> KeyValueListParser.parseCommaDelimitedKeyValuePairs(
							taskLaunchRequestProperties.getDeploymentProperties())
			);

			this.taskNameSupplier(()->taskLaunchRequestProperties.getTaskName());
		}
	}

	static class ExpressionEvaluatingCommandLineArgsMapper implements CommandLineArgumentsMessageMapper {
		private final Map<String,Expression> argExpressionsMap;

		ExpressionEvaluatingCommandLineArgsMapper(String argExpressions) {
			argExpressionsMap = new HashMap<>();
			if (StringUtils.hasText(argExpressions)) {
				SpelExpressionParser expressionParser = new SpelExpressionParser();

				KeyValueListParser.parseCommaDelimitedKeyValuePairs(argExpressions).forEach(
						(k,v)-> argExpressionsMap.put(k, expressionParser.parseExpression(v)));
			}

		}

		@Override
		public Collection<String> processMessage(Message<?> message) {
			return evaluateArgExpressions(message, argExpressionsMap);
		}

		private Collection<String> evaluateArgExpressions(Message<?> message, Map<String, Expression> argExpressions) {
			List<String> results = new LinkedList<>();
			argExpressions.forEach((k, v) -> {
				Expression expression = argExpressionsMap.get(k);
				Assert.notNull(expression, String.format("expression %s cannot be null!", v));
				results.add(String.format("%s=%s", k, expression.getValue(message)));
			});
			return results;
		}
	}

}
