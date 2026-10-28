package io.github.hectorvent.floci.services.ec2.model;

import java.util.List;

/** A page of account- and region-scoped volumes, with a continuation token when more remain. */
public record VolumeListResult(List<Volume> volumes, String nextToken) {}
