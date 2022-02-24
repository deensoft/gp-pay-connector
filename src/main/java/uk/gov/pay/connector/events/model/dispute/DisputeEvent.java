package uk.gov.pay.connector.events.model.dispute;

import uk.gov.pay.connector.events.eventdetails.EventDetails;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.events.model.ResourceType;

import java.time.ZonedDateTime;

public class DisputeEvent extends Event {
    private String parentResourceExternalId;
    private String serviceId;
    private Boolean live;
    public DisputeEvent(String resourceExternalId, String parentResourceExternalId, String serviceId, Boolean live,
                        EventDetails eventDetails, ZonedDateTime disputeCreated) {
        super(resourceExternalId, eventDetails, disputeCreated);
        this.parentResourceExternalId = parentResourceExternalId;
        this.serviceId = serviceId;
        this.live = live;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.DISPUTE;
    }

    public String getParentResourceExternalId() {
        return parentResourceExternalId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public Boolean getLive() {
        return live;
    }
}
