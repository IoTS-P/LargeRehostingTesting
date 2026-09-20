//
// Created by colin on 25-5-21.
//

#include "ELFLoader.h"

#include <utility>
#include <algorithm>

/**
 * ELF loader
 *
 * @param file Input load property
 * @param output output path of ELF
 *
 * Input load property format:
 *
 * 1. machine: 2 bytes
 * 2. word_size: 1 byte
 * 3. endianness: 1 byte
 * 4. entry_point: 8 bytes
 * 5. sections:
 *   - (1) image base: 8 bytes
 *   - (2) size: 8 bytes
 *   - (3) content: <size> bytes
 */
ELFLoader::ELFLoader(std::ifstream &file, std::string output) : output_path(std::move(output)) {
    char buffer[100];

    file.read(buffer, sizeof(this->machine));
    this->machine = *reinterpret_cast<short*>(buffer);

    file.read(buffer, sizeof(this->word_size));
    this->word_size = *reinterpret_cast<unsigned char*>(buffer);

    file.read(buffer, sizeof(this->endianness));
    this->endianness = *reinterpret_cast<unsigned char*>(buffer);

    file.read(buffer, sizeof(this->entry_point));
    this->entry_point = *reinterpret_cast<unsigned long*>(buffer);

    this->sections = std::vector<mem_section>();

    while (true) {
        auto sec = mem_section();

        file.read(buffer, sizeof(sec.image_base));
        if (file.eof())
            break;
        sec.image_base = *reinterpret_cast<unsigned long*>(buffer);

        file.read(buffer, sizeof(sec.size));
        sec.size = *reinterpret_cast<unsigned long*>(buffer);

        file.read(buffer, sizeof(sec.is_mapped));
        sec.is_mapped = *reinterpret_cast<bool*>(buffer);

        file.read(buffer, sizeof(sec.flag));
        sec.flag = *reinterpret_cast<unsigned int*>(buffer);

        for (int i=0; i<100; i++) {
            file.read(&buffer[i], 1);
            if (buffer[i] == '\0')
                break;
            if (i == 99)
                throw "Section name too long";
        }

        sec.name = buffer;

        if (sec.is_mapped) {
            const auto content = new char[sec.size];
            file.read(content, sec.size);
            sec.content = content;
        }

        sections.push_back(sec);
    }
}

void ELFLoader::buildELF() {
    builder.create(this->word_size, this->endianness);      // Set word size and endianness
    builder.set_machine(this->machine);             // Set machine
    builder.set_entry(this->entry_point);           // Set entry point
    builder.set_os_abi(ELFOSABI_LINUX);    // Set ABI to linux
    builder.set_type(ET_EXEC);

    // auto segments = calculate_segments();

    // Create load section, containing all bytes of firmware
    for (auto &sec : this->sections) {
        section* load_sec = builder.sections.add(sec.name);
        segment* load_seg = builder.segments.add();
        load_seg->add_section(load_sec, load_sec->get_addr_align());
        load_seg->set_type(PT_LOAD);
        std::cout << "Adding section " << sec.name << " with size " << sec.size << " bytes" << std::endl;
        load_sec->set_flags(sec.flag);
        load_seg->set_flags(sec.flag);
        load_sec->set_address(sec.image_base);
        load_seg->set_virtual_address(sec.image_base);
        load_seg->set_physical_address(sec.image_base);
        if (sec.is_mapped) {
            load_sec->set_type(SHT_PROGBITS);
            load_sec->set_data(sec.content, sec.size);
        } else {
            load_sec->set_type(SHT_NOBITS);
            load_sec->set_size(sec.size);
            load_seg->set_memory_size(sec.size);
        }
    }

    // for (auto &[fst, snd]: segments) {
    //     segment* load_seg = builder.segments.add();
    //     std::cout << "Adding segment with start address " << fst << " - " << snd << std::endl;
    //     load_seg->set_type(PT_LOAD);
    //     load_seg->set_virtual_address(fst);
    //     load_seg->set_physical_address(fst);
    //     load_seg->set_flags(PF_R | PF_W | PF_X);
    //     for (auto &sec: builder.sections) {
    //         if (fst <= sec->get_address() && sec->get_address() < fst + snd)
    //             load_seg->add_section(sec.get(), sec->get_addr_align());
    //     }
    // }
    builder.save(this->output_path);
}

std::vector<std::pair<unsigned long, unsigned long>> ELFLoader::calculate_segments() {
    auto segments = std::vector<std::pair<unsigned long, unsigned long>>(); // start address, end address

    auto sections = std::vector<std::pair<unsigned long, unsigned long>>(); // start address, size
    for (auto &sec: this->sections)
        sections.emplace_back(sec.image_base, sec.size);

    // sort sections by image base from small to big
    std::sort(sections.begin(), sections.end(),
        [](const std::pair<unsigned long, unsigned long> &a, const std::pair<unsigned long, unsigned long> &b) {
            return a.first < b.first;
        });

    int start = 0;
    for (int i=0; i<sections.size() - 1; i++) {
        if (sections[i].first + sections[i].second != sections[i+1].first) {
            segments.emplace_back(sections[start].first, sections[i].first + sections[i].second);
            start = i + 1;
        }
    }

    if (sections.size() >= 2) {
        if (sections[sections.size() - 2].first + sections[sections.size() - 2].second
            != sections[sections.size() - 1].first)
            segments.emplace_back(sections[sections.size() - 1].first,
                sections[sections.size() - 1].first + sections[sections.size() - 1].second);
    }

    return segments;
}