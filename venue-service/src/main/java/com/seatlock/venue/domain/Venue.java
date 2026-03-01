package com.seatlock.venue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "venues")
public class Venue implements Persistable<UUID> {

    @Id
    private UUID venueId = UUID.randomUUID();

    private String name;
    private String address;
    private String city;
    private String state;

    @Enumerated(EnumType.STRING)
    private VenueStatus status = VenueStatus.ACTIVE;

    private Instant createdAt = Instant.now();

    @Transient
    private boolean isNew = true;

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return venueId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getVenueId() { return venueId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public VenueStatus getStatus() { return status; }
    public void setStatus(VenueStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
