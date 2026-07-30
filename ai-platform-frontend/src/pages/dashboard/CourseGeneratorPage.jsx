import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../../api';
import {
    Typography,
    Card,
    CardBody,
    Input,
    Button,
    Select,
    Option,
    Spinner,
    Alert,
    Chip,
    IconButton,
} from "@material-tailwind/react";
import {
    SparklesIcon,
    ExclamationTriangleIcon,
    TrashIcon,
    BookOpenIcon,
    AcademicCapIcon
} from '@heroicons/react/24/solid';
import toast from 'react-hot-toast';

const CourseGeneratorPage = () => {
    const [topic, setTopic] = useState('');
    const [level, setLevel] = useState('Beginner');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [courses, setCourses] = useState([]);
    const [loadingCourses, setLoadingCourses] = useState(true);
    const navigate = useNavigate();

    useEffect(() => {
        fetchCourses();
    }, []);

    const fetchCourses = async () => {
        try {
            const response = await api.get('/courses');
            setCourses(response.data);
        } catch (err) {
            console.error("Failed to load courses:", err);
        } finally {
            setLoadingCourses(false);
        }
    };

    const handleGenerate = async (e) => {
        e.preventDefault();
        setLoading(true);
        setError(null);

        if (!topic.trim()) {
            setError("Please enter a topic.");
            setLoading(false);
            return;
        }

        try {
            const response = await api.post('/courses/generate', { topic, level });
            // Refresh list after generation
            fetchCourses();
            setTopic('');
            toast.success("Course Generated Successfully!");
        } catch (err) {
            setError(err.response?.data?.error || "Failed to generate course. Please try again.");
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async (courseId, e) => {
        e.stopPropagation(); // Prevent card click
        if (!window.confirm("Are you sure you want to delete this course?")) return;

        try {
            await api.delete(`/courses/${courseId}`);
            setCourses(courses.filter(c => c.id !== courseId));
            toast.success("Course deleted successfully");
        } catch (err) {
            console.error("Failed to delete course:", err);
            toast.error("Failed to delete course. Please try again.");
        }
    };

    return (
        <div className="mt-12 space-y-8">
            <div className="mb-6 flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
                <Typography variant="h6" color="blue-gray" className="dark:text-white">
                    Course Generator
                </Typography>
            </div>

            <Card className="border border-blue-gray-100 dark:border-gray-700 shadow-sm bg-white/80 dark:bg-gray-900/80 backdrop-blur-md">
                <CardBody className="p-6">
                    <div className="mb-6">
                        <Typography variant="h5" color="blue-gray" className="mb-1 dark:text-white">
                            Generate a New Course
                        </Typography>
                        <Typography variant="small" className="font-normal text-blue-gray-600 dark:text-gray-400">
                            Enter a topic and get a personalized verified course for you.
                        </Typography>
                    </div>

                    <form onSubmit={handleGenerate} className="flex flex-col gap-6">
                        <div>
                            <Typography variant="small" color="blue-gray" className="mb-2 font-medium dark:text-gray-300">
                                What do you want to learn?
                            </Typography>
                            <Input
                                size="lg"
                                placeholder="e.g., Python Basics, Ancient History, Quantum Physics"
                                value={topic}
                                onChange={(e) => setTopic(e.target.value)}
                                className="!border-blue-gray-200 focus:!border-blue-500 dark:!text-white dark:!border-gray-700 dark:bg-gray-800/50"
                                labelProps={{
                                    className: "before:content-none after:content-none",
                                }}
                                disabled={loading}
                            />
                        </div>

                        <div>
                            <Typography variant="small" color="blue-gray" className="mb-2 font-medium dark:text-gray-300">
                                Difficulty Level
                            </Typography>
                            <Select
                                size="lg"
                                value={level}
                                onChange={(val) => setLevel(val)}
                                className="border-blue-gray-200 dark:border-gray-700 dark:text-white"
                                labelProps={{
                                    className: "before:content-none after:content-none",
                                }}
                                disabled={loading}
                                menuProps={{
                                    className: "bg-white dark:bg-gray-900 border border-blue-gray-50 dark:border-gray-800 text-blue-gray-900 dark:text-white"
                                }}
                            >
                                <Option value="Beginner">Beginner</Option>
                                <Option value="Intermediate">Intermediate</Option>
                                <Option value="Advanced">Advanced</Option>
                            </Select>
                        </div>

                        {error && (
                            <Alert color="red" icon={<ExclamationTriangleIcon className="h-5 w-5" />}>
                                {error}
                            </Alert>
                        )}

                        <Button
                            type="submit"
                            disabled={loading}
                            fullWidth
                            className="flex items-center justify-center gap-2 bg-gradient-to-r from-blue-600 to-blue-500 shadow-blue-500/20 hover:shadow-blue-500/40"
                        >
                            {loading ? <Spinner className="h-4 w-4" /> : <SparklesIcon className="h-4 w-4" />}
                            Generate Course
                        </Button>
                    </form>
                </CardBody>
            </Card>

            {/* My Courses Section */}
            <div className="space-y-6">
                <div>
                    <Typography variant="h5" color="blue-gray" className="mb-1 dark:text-white">
                        My Courses
                    </Typography>
                    <Typography variant="small" className="font-normal text-blue-gray-600 dark:text-gray-400">
                        Manage your generated courses
                    </Typography>
                </div>

                {loadingCourses ? (
                    <div className="flex justify-center py-12">
                        <Spinner className="h-8 w-8 text-blue-500" />
                    </div>
                ) : courses.length === 0 ? (
                    <Card className="border border-blue-gray-100 dark:border-gray-700 shadow-sm bg-white/80 dark:bg-gray-900/80 backdrop-blur-md">
                        <CardBody className="text-center py-12">
                            <BookOpenIcon className="h-12 w-12 text-blue-gray-200 dark:text-gray-700 mx-auto mb-4" />
                            <Typography color="blue-gray" variant="h6" className="dark:text-white">
                                No courses yet
                            </Typography>
                            <Typography color="gray" className="font-normal mt-1 dark:text-gray-400">
                                Generate your first course above!
                            </Typography>
                        </CardBody>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {courses.map((course) => (
                            <Card
                                key={course.id}
                                className="cursor-pointer border border-blue-gray-100 dark:border-gray-700 hover:shadow-md transition-all group bg-white/80 dark:bg-gray-900/80 backdrop-blur-md"
                                onClick={() => navigate(`/dashboard/course/${course.id}`)}
                            >
                                <CardBody className="p-5 relative">
                                    <div className="absolute top-4 right-4 z-10">
                                        <IconButton
                                            variant="text"
                                            color="red"
                                            size="sm"
                                            className="hover:bg-red-50 dark:hover:bg-red-900/20 opacity-0 group-hover:opacity-100 transition-opacity"
                                            onClick={(e) => handleDelete(course.id, e)}
                                        >
                                            <TrashIcon className="h-5 w-5" />
                                        </IconButton>
                                    </div>

                                    <div className="flex justify-between items-start mb-3">
                                        <div className="p-2 bg-blue-50 dark:bg-blue-900/20 rounded-lg text-blue-500">
                                            <AcademicCapIcon className="h-6 w-6" />
                                        </div>
                                        <Chip
                                            size="sm"
                                            variant="ghost"
                                            value={course.level}
                                            className={`rounded-full ${course.level === 'Advanced'
                                                    ? 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300'
                                                    : course.level === 'Intermediate'
                                                        ? 'bg-amber-50 text-amber-700 dark:bg-amber-900/20 dark:text-amber-300'
                                                        : 'bg-green-50 text-green-700 dark:bg-green-900/20 dark:text-green-300'
                                                }`}
                                        />
                                    </div>

                                    <Typography variant="h6" color="blue-gray" className="mb-1 group-hover:text-blue-500 transition-colors dark:text-white line-clamp-1">
                                        {course.topic}
                                    </Typography>

                                    <Typography className="font-normal text-gray-600 dark:text-gray-400 line-clamp-2 text-sm mb-4">
                                        Generated on {new Date(course.createdAt).toLocaleDateString()}
                                    </Typography>

                                    <div className="pt-4 border-t border-blue-gray-50 dark:border-gray-800">
                                        <div className="flex items-center justify-between">
                                            <Typography variant="small" className="font-medium text-blue-gray-600 dark:text-gray-300">
                                                Active Course
                                            </Typography>
                                        </div>
                                    </div>
                                </CardBody>
                            </Card>
                        ))}
                    </div>
                )}
            </div>

            <div className="mt-8 text-center text-blue-gray-500 text-sm dark:text-gray-500">
                <p>Powered by Smart Learning Engine</p>
            </div>
        </div>
    );
};

export default CourseGeneratorPage;
