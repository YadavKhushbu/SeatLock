-- ---------------------------------------------------------------------------
-- Demo data, so a fresh clone has something to book against immediately.
--
-- Idempotent and self-contained: safe to run against an empty database, and
-- Flyway will never run it twice against the same one.
-- ---------------------------------------------------------------------------

INSERT INTO venues (id, name, city, address) VALUES
    (1, 'Prithvi Theatre',      'Mumbai',    '20 Janki Kutir, Juhu'),
    (2, 'Ravindra Natya Mandir','Mumbai',    'Sayani Road, Prabhadevi'),
    (3, 'Chowdiah Memorial',    'Bengaluru', 'Gayathri Devi Park Extension');
SELECT setval(pg_get_serial_sequence('venues', 'id'), 3);

-- Three sections per venue, six rows of ten seats each: 180 seats per venue.
INSERT INTO seats (venue_id, section, row_label, seat_number)
SELECT v.id, s.section, r.row_label, n.seat_number
FROM   venues v
CROSS JOIN (VALUES ('ORCHESTRA'), ('MEZZANINE'), ('BALCONY')) AS s(section)
CROSS JOIN (VALUES ('A'), ('B'), ('C'), ('D'), ('E'), ('F'))  AS r(row_label)
CROSS JOIN generate_series(1, 10) AS n(seat_number);

INSERT INTO events (id, venue_id, title, description, starts_at, sales_open_at, sales_close_at, status) VALUES
    (1, 1, 'Tuesdays with Morrie',
        'A two-hander about an old teacher, a former student, and the fourteen Tuesdays between them.',
        now() + interval '14 days', now() - interval '1 day', now() + interval '13 days', 'SCHEDULED'),
    (2, 2, 'Indian Ocean: Acoustic',
        'The band strips four decades of material back to voices, bass and a single tabla.',
        now() + interval '30 days', now() - interval '2 days', now() + interval '29 days', 'SCHEDULED'),
    (3, 3, 'The Lives of Others (Screening)',
        'A restored 35mm print, introduced by the film society.',
        now() + interval '7 days',  now() - interval '5 days', now() + interval '6 days',  'SCHEDULED');
SELECT setval(pg_get_serial_sequence('events', 'id'), 3);

-- Price by section: the closer to the stage, the more it costs.
INSERT INTO event_seats (event_id, seat_id, price_cents, status)
SELECT e.id,
       s.id,
       CASE s.section
           WHEN 'ORCHESTRA' THEN 250000
           WHEN 'MEZZANINE' THEN 150000
           ELSE                  80000
       END,
       'AVAILABLE'
FROM   events e
JOIN   seats  s ON s.venue_id = e.venue_id;
