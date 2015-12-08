package de.zalando.aruha.nakadi.controller;

import static java.util.Optional.ofNullable;
import static org.springframework.http.ResponseEntity.ok;
import static org.springframework.http.ResponseEntity.status;

import com.google.common.collect.ImmutableMap;
import de.zalando.aruha.nakadi.repository.EventConsumer;
import de.zalando.aruha.nakadi.service.EventStream;
import de.zalando.aruha.nakadi.service.EventStreamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codahale.metrics.annotation.Timed;

import de.zalando.aruha.nakadi.NakadiException;
import de.zalando.aruha.nakadi.domain.Problem;
import de.zalando.aruha.nakadi.domain.Topic;
import de.zalando.aruha.nakadi.domain.TopicPartition;
import de.zalando.aruha.nakadi.repository.TopicRepository;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping(value = "/topics")
public class TopicsController {

	private static final Logger LOG = LoggerFactory.getLogger(TopicsController.class);

	@Autowired
	private TopicRepository topicRepository;

	@Autowired
	private TaskExecutor taskExecutor;

    @Value("${nakadi.stream.timeoutMs}")
    private long nakadiStreamTimeout;

	@Timed
	@RequestMapping(value = "/", method = RequestMethod.GET)
	@ApiOperation("Lists all known topics")

	// FIXME: response for 200 does not match reality
	@ApiResponses({ @ApiResponse(code = 200, message = "Returns list of all topics", response = Topic.class),
			@ApiResponse(code = 401, message = "User not authenticated", response = Problem.class),
			@ApiResponse(code = 503, message = "Not available", response = Problem.class) })
	public ResponseEntity<?> listTopics() {
		try {
			return ok().body(topicRepository.listTopics());
		} catch (final NakadiException e) {
			return status(503).body(e.getProblemMessage());
		}
	}

	@Timed
	@RequestMapping(value = "/{topicId}/partitions", method = RequestMethod.GET)
	public ResponseEntity<?> listPartitions(@PathVariable("topicId") final String topicId) {
		try {
			return ok().body(topicRepository.listPartitions(topicId));
		} catch (final NakadiException e) {
			return status(503).body(e.getProblemMessage());
		}
	}

	@Timed
	@RequestMapping(value = "/{topicId}/partitions/{partitionId}", method = RequestMethod.GET)
	public TopicPartition getPartition(@PathVariable("topicId") final String topicId) {
		throw new UnsupportedOperationException();
	}

	@Timed
	@RequestMapping(value = "/{topicId}/partitions/{partitionId}/events", method = RequestMethod.POST)
	public ResponseEntity<?> postEventToPartition(@PathVariable("topicId") final String topicId,
			@PathVariable("partitionId") final String partitionId, @RequestBody final String messagePayload) {
		LOG.trace("Event received: {}, {}, {}", new Object[] { topicId, partitionId, messagePayload });
		try {
			topicRepository.postEvent(topicId, partitionId, messagePayload);
			return ok().build();
		} catch (final NakadiException e) {
			LOG.error("error posting to partition", e);
			return status(500).body(e.getProblemMessage());
		}
	}

	@Timed
	@RequestMapping(value = "/{topicId}/partitions/{partitionId}/events", method = RequestMethod.GET)
	public ResponseEntity<?> readEventFromPartition(@PathVariable("topicId") final String topicId,
			@PathVariable("partitionId") final String partitionId, @RequestBody final String messagePayload) {
		// LOG.trace("Event received: {}, {}, {}", new Object[] { topicId,
		// partitionId, messagePayload });
		topicRepository.readEvent(topicId, partitionId);
		return ok().build();
	}

	@RequestMapping(value = "/{topic}/partitions/{partition}/events/stream", method = RequestMethod.GET)
	public ResponseBodyEmitter streamEventsFromPartition(
			@PathVariable("topic") final String topic,
			@PathVariable("partition") final String partition,
			@RequestParam("start_from") final String startFrom,
			@RequestParam(value = "batch_limit", required = false, defaultValue = "1") final Integer batchLimit,
			@RequestParam(value = "stream_limit", required = false) final Integer streamLimit,
			@RequestParam(value = "batch_flush_timeout", required = false) final Integer batchTimeout,
			@RequestParam(value = "stream_timeout", required = false) final Integer streamTimeout,
			@RequestParam(value = "batch_keep_alive_limit", required = false) final Integer batchKeepAliveLimit,
            final HttpServletResponse response) throws IOException {

        final ResponseBodyEmitter responseEmitter = new ResponseBodyEmitter(nakadiStreamTimeout);

        try {
            // check if topic exists
            final boolean topicExists = topicRepository.listTopics().stream().anyMatch(t -> topic.equals(t.getName()));
            if (!topicExists) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                responseEmitter.send(new Problem("topic not found"));
                responseEmitter.complete();
            }

            // check if partition exists (todo)
            // check if offset is correct (todo)

            final EventStreamConfig streamConfig = EventStreamConfig.builder()
                    .withTopic(topic)
                    .withCursors(ImmutableMap.of(partition, startFrom))
                    .withBatchLimit(batchLimit)
                    .withStreamLimit(ofNullable(streamLimit))
                    .withBatchTimeout(ofNullable(batchTimeout))
                    .withStreamTimeout(ofNullable(streamTimeout))
                    .withBatchKeepAliveLimit(ofNullable(batchKeepAliveLimit))
                    .build();

            final EventConsumer eventConsumer = topicRepository.createEventConsumer(topic, streamConfig.getCursors());

            final EventStream eventStreamTask = new EventStream(eventConsumer, responseEmitter, streamConfig);
            taskExecutor.execute(eventStreamTask);

            response.setStatus(HttpStatus.OK.value());
        }
        catch (NakadiException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            responseEmitter.send(e.asProblem());
            responseEmitter.complete();
        }
        return responseEmitter;
	}
}
