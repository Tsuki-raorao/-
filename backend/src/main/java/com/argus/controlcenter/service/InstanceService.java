package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.NotFoundException;
import com.argus.controlcenter.repository.InstanceRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class InstanceService {
    private final InstanceRepository repository;
    public InstanceService(InstanceRepository repository) { this.repository=repository; }
    public List<Instance> findAll() { return repository.findAll(); }
    public Instance findById(String id) { return repository.findById(id).orElseThrow(() -> new NotFoundException("instance not found: " + id)); }
    public long count() { return repository.count(); }
    public void seed() {
        if (findAll().isEmpty()) {
            Instance value = new Instance("mc01", "零壹", "node-local", "mc01", "1.21.1-NeoForge", InstanceStatus.RUNNING, Instant.now(), 0, 0, 0);
            value.setDataSource("MOCK");
            value.setMetricsStatus(MetricsStatus.AVAILABLE);
            value.setSampledAt(Instant.now());
            value.setLastSeenAt(Instant.now());
            repository.save(value);
        }
    }
}
