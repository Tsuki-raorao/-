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
    public Instance applyAction(String id, String action) {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        Instance instance=findById(id); String normalized=action.trim().toUpperCase(Locale.ROOT);
        if (!(normalized.equals("START") || normalized.equals("STOP") || normalized.equals("RESTART"))) throw new IllegalArgumentException("action must be START, STOP or RESTART");
        instance.setStatus(normalized.equals("STOP") ? InstanceStatus.STOPPED : InstanceStatus.RUNNING); instance.setUpdatedAt(Instant.now()); return repository.save(instance);
    }
    public long count() { return repository.count(); }
    public void seed() { if (findAll().isEmpty()) repository.save(new Instance("mc01", "零壹", "node-local", "mc01", "1.21.1-NeoForge", InstanceStatus.RUNNING, Instant.now())); }
}
